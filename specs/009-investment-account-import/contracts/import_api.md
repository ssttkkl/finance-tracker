# API Contract: Investment Statement Import

**Feature**: 009-investment-account-import  
**Date**: 2026-07-23

## InvestmentImportService

Application service orchestrating investment statement import with atomic transaction and dual-backend support.

### Constructor

```python
class InvestmentImportService:
    def __init__(self, unit_of_work, parser_registry):
        """
        Args:
            unit_of_work: RelationalUnitOfWork instance (workspace-bound)
            parser_registry: Dict[str, InvestmentStatementParser] mapping source → parser
        """
```

### Method: `import_investment_statement`

```python
def import_investment_statement(self, command: ImportInvestmentStatementCommand) -> OperationResult:
    """
    Import investment statement from file or API, creating events and updating snapshot.
    
    Args:
        command: ImportInvestmentStatementCommand with source, path, account, etc.
    
    Returns:
        OperationResult with:
            - ok: True on success (new import or idempotent duplicate)
            - count: Number of new investment events created
            - message: "imported" or "already imported"
            - details: {
                "batch_id": UUID,
                "duplicate": bool,
                "by_account": Dict[str, int],  # account_name → event_count
                "event_ids": List[UUID],
                "snapshot_summary": Dict[str, Any]  # positions, cash
              }
    
    Raises:
        ValueError: Invalid command (account not found, type mismatch, parse error)
        StorageError: Database unavailable or transaction conflict
    
    Transaction:
        - All-or-nothing: batch, raw_records, events, snapshot update
        - Rollback on any error (parse, validation, constraint violation)
    
    Idempotency:
        - Same source_digest → return existing batch_id, ok=True, count=0
        - Same source_identity (within or across batches) → unique constraint prevents duplicates
    """
```

### ImportInvestmentStatementCommand

```python
@dataclass(frozen=True)
class ImportInvestmentStatementCommand:
    """Command to import investment statement."""
    
    source: str  # Parser identifier (e.g., 'dfzq', 'binance')
    source_path: str  # File path or API query descriptor
    account_name: str | None  # Target account (None if multi-account)
    currency: str | None  # Override currency (None = auto-detect)
    since: str | None  # Start date for API sync (YYYY-MM-DD)
    until: str | None  # End date for API sync (YYYY-MM-DD)
    password: str | None  # PDF password (for encrypted files)
    dry_run: bool = False  # Parse only, no database writes
```

## InvestmentStatementParser (Interface)

Abstract parser interface that each broker/exchange implements.

### Interface

```python
from typing import Protocol, Iterator

class InvestmentStatementParser(Protocol):
    """Parser for investment statements (files or APIs)."""
    
    def parse(self, command: ImportInvestmentStatementCommand) -> Iterator[dict]:
        """
        Parse statement and yield raw investment records.
        
        Args:
            command: Import command with source_path, password, etc.
        
        Yields:
            dict: Raw investment record with keys:
                - source_identity: str (unique business key)
                - date: str (ISO 8601 timestamp)
                - action: str (BUY, SELL, SWAP, DEPOSIT, WITHDRAW, DIVIDEND, CHECKIN)
                - ticker: str | None
                - shares: Decimal | str
                - price: Decimal | str
                - amount: Decimal | str
                - commission: Decimal | str
                - currency: str
                - account_name: str
                - note: str
                - from_ticker: str | None (for SWAP)
                - to_ticker: str | None (for SWAP)
                - from_amount: Decimal | str (for SWAP)
                - to_amount: Decimal | str (for SWAP)
                - commission_asset: str | None
        
        Raises:
            ValueError: Parse error (unsupported format, corrupted file)
            FileNotFoundError: Source file not found
            PermissionError: Cannot read file or access API
        
        Notes:
            - Decimal values MAY be returned as Decimal or str (service normalizes)
            - source_identity MUST be unique within source type
            - account_name MUST be set (parser resolves from statement or command)
        """
```

### Concrete Parsers

#### DFZQStatementParser

```python
class DFZQStatementParser:
    """东方证券 PDF statement parser."""
    
    def parse(self, command: ImportInvestmentStatementCommand) -> Iterator[dict]:
        """
        Parse DFZQ PDF via qpdf + mutool.
        
        Process:
            1. Decrypt PDF if password provided (qpdf --decrypt)
            2. Extract text (mutool draw -F text)
            3. Parse text lines (dfzq.parse_dfzq_text)
            4. Map to unified schema
            5. Construct source_identity per record
        
        source_identity format: "dfzq:{date}:{ticker}:{action}:{amount}:{balance}"
        
        Raises:
            ValueError: PDF format unrecognized, required tools missing
        """
```

#### ExchangeStatementParser

```python
class ExchangeStatementParser:
    """Generic ccxt exchange parser (Binance, OKX, etc.)."""
    
    def __init__(self, provider: str):
        """
        Args:
            provider: ccxt provider name ('binance', 'okx', etc.)
        """
    
    def parse(self, command: ImportInvestmentStatementCommand) -> Iterator[dict]:
        """
        Fetch trades via ccxt API.
        
        Process:
            1. Load credentials (env vars or ~/.ft/credentials.json)
            2. Build ccxt client
            3. Paginate fetch_my_trades (since → until)
            4. Map ccxt trade schema to unified schema
            5. Construct source_identity per trade
        
        source_identity format: "ccxt:{provider}:trade:{trade_id}"
        
        Mapping:
            - USDT/USD quote → BUY/SELL (single event)
            - Crypto pairs → SWAP (single event with from/to)
            - Fee handling: commission + commission_asset
        
        Raises:
            ValueError: Credentials missing, API error, unsupported provider
        """
```

#### PolymarketStatementParser

```python
class PolymarketStatementParser:
    """Polymarket Activity API parser."""
    
    def parse(self, command: ImportInvestmentStatementCommand) -> Iterator[dict]:
        """
        Fetch activities via public Activity API.
        
        Process:
            1. Resolve proxy wallet (from command or credentials)
            2. Paginate activity endpoint
            3. Filter TRADE activities
            4. Map to unified schema (ticker = pm:{slug}:{outcome})
            5. Construct source_identity per activity
        
        source_identity format: "polymarket:tx:{tx_hash}"
        
        Raises:
            ValueError: Wallet missing, API error, unexpected activity shape
        """
```

## OperationResult

Standard return type for application services (existing from domain/application.py).

```python
@dataclass
class OperationResult:
    ok: bool
    message: str
    count: int = 0
    details: dict | None = None
```

## Unit of Work (Extended)

RelationalUnitOfWork gains access to InvestmentRepository for event writes.

### investments Property

```python
@property
def investments(self) -> RelationalInvestmentRepository:
    """
    Repository for investment event writes.
    
    Methods:
        - add_event(account_id, raw_record_id, event_row) -> UUID
        - list_events(account_id, since, until) -> List[dict]
    """
```

## RelationalInvestmentRepository (Extended)

Existing repository gains event-from-raw-record write method.

### Method: `add_event`

```python
def add_event(
    self,
    *,
    account_id: UUID,
    raw_record_id: UUID | None,
    occurred_at: datetime,
    kind: str,
    action: str,
    ticker: str | None,
    amount: Decimal | None,
    price: Decimal | None,
    commission: Decimal | None,
    currency: str,
    payload: dict,
) -> UUID:
    """
    Create investment event from imported record.
    
    Args:
        account_id: Target investment account
        raw_record_id: Source raw_record (NULL for manual CLI entries)
        occurred_at: Event timestamp
        kind: 'security' or 'crypto'
        action: Event action (deposit, withdraw, swap, dividend, checkin)
        ticker: Primary asset ticker (None for pure cash events)
        amount: Simplified amount (None for complex events like swap)
        price: Unit price (None for non-trade events)
        commission: Fee amount (None or 0 if no commission)
        currency: Base currency
        payload: Full event details (JSON)
    
    Returns:
        UUID: Created event ID
    
    Raises:
        IntegrityError: Duplicate (workspace_id, raw_record_id)
        ValueError: Invalid account_id or field validation failure
    
    Notes:
        - MUST enforce unique (workspace_id, raw_record_id) if raw_record_id NOT NULL
        - payload MUST be JSON-serializable
        - Decimal fields stored as NUMERIC(28,10)
    """
```

### Method: `list_events` (Read)

```python
def list_events(
    self,
    *,
    account_id: UUID | None = None,
    since: datetime | None = None,
    until: datetime | None = None,
    order: str = "asc",
) -> List[dict]:
    """
    Query investment events for reporting/audit.
    
    Args:
        account_id: Filter by account (None = all accounts in workspace)
        since: Start timestamp (inclusive)
        until: End timestamp (inclusive)
        order: "asc" or "desc" by occurred_at
    
    Returns:
        List of event dicts with all fields + account_name, raw_record source_identity
    """
```

## Snapshot Validation

### Function: `validate_investment_snapshot`

```python
from decimal import Decimal
import math

def validate_investment_snapshot(snapshot: dict) -> None:
    """
    Validate that all numeric values in investment snapshot are finite.
    
    Args:
        snapshot: Snapshot dict from apply_investment_event() replay
    
    Raises:
        ValueError: If any shares, total_cost, or cash is NaN/Infinity
    
    Checks:
        - All position.shares are finite Decimals
        - All position.total_cost are finite Decimals
        - Shares MAY be negative (short positions) but MUST be finite
        - Cost MAY be negative but MUST be finite
    
    Example error:
        ValueError: "Position '600000.sh' in account '东方证券' has invalid shares: NaN"
    """
    security = snapshot.get("accounts", {}).get("security", {})
    crypto = snapshot.get("accounts", {}).get("crypto", {})
    
    for account_type, accounts in [("security", security), ("crypto", crypto)]:
        for account_name, account in accounts.items():
            positions = account.get("positions", {})
            for ticker, position in positions.items():
                shares = Decimal(str(position.get("shares", "0")))
                total_cost = Decimal(str(position.get("total_cost", "0")))
                
                if not shares.is_finite():
                    raise ValueError(
                        f"Position '{ticker}' in account '{account_name}' has "
                        f"invalid shares: {shares}"
                    )
                
                if not total_cost.is_finite():
                    raise ValueError(
                        f"Position '{ticker}' in account '{account_name}' has "
                        f"invalid total_cost: {total_cost}"
                    )
```

## Transaction Flow

### Happy Path

```
1. CLI: ft import dfzq.pdf --source dfzq --account 东方证券
2. CLI router → InvestmentImportService.import_investment_statement(command)
3. Service: Read file, compute SHA256 digest
4. Service: with uow:
5.   uow.imports.start_batch(source_kind='dfzq', source_digest=digest, ...)
6.   If batch.status == 'completed': return idempotent success
7.   parser = registry['dfzq']
8.   records = list(parser.parse(command))  # Yields dicts
9.   raw_file_id = uow.imports.add_raw_file(batch_id, path, digest, ...)
10.  raw_record_ids = uow.imports.add_raw_records(batch_id, raw_file_id, records)
11.  snapshot = uow.snapshot.load(lock=True)
12.  for record, raw_record_id in zip(records, raw_record_ids):
13.    event_row = normalize_event(record)
14.    apply_investment_event(snapshot, event_row, default_currency=...)
15.    event_id = uow.investments.add_event(account_id, raw_record_id, event_row)
16.  validate_investment_snapshot(snapshot)
17.  uow.snapshot.save(snapshot)
18.  uow.imports.complete_batch(batch_id)
19.  uow.commit()
20. Return OperationResult(ok=True, count=len(records), batch_id=...)
```

### Idempotent Duplicate

```
1-5. [same]
6. batch.status == 'completed':
     uow.commit()
     return OperationResult(ok=True, count=0, duplicate=True, batch_id=batch_id)
```

### Parse Error

```
1-7. [same]
8. parser.parse(command) raises ValueError("Page 3, line 127: unexpected format")
9. Exception propagates to CLI
10. CLI catches ValueError, formats user-friendly error, exits 2
11. No database writes (batch not created if parse fails before start_batch)
```

### Validation Error

```
1-15. [same]
16. validate_investment_snapshot(snapshot) raises ValueError("Position '600000.sh' has invalid shares: -100")
17. Exception propagates
18. with uow context manager catches exception, calls uow.rollback()
19. Return error OperationResult or raise (CLI formats error)
```

### Unique Constraint Violation (Duplicate source_identity Across Batches)

```
1-10. [same]
11. uow.imports.add_raw_records(...) raises IntegrityError (UNIQUE constraint on source_identity)
12. with uow catches, rollback
13. Return error OperationResult with specific message: "Duplicate records detected (already imported in batch <id>)"
```

## Error Handling Philosophy

1. **Fail Fast**: Parse errors detected before database writes
2. **All-or-Nothing**: Transaction rollback prevents partial facts
3. **Specific Errors**: ValueError with exact location (page/line for parse, field for validation)
4. **Actionable Messages**: Tell user how to fix (install tool, create account, etc.)
5. **Idempotency**: Duplicate imports succeed with count=0, not error
