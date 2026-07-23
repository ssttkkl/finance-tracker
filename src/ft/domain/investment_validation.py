"""Investment snapshot validation functions.

Ensures financial correctness by rejecting non-finite values that would
corrupt projection state.
"""
from decimal import Decimal


def validate_investment_snapshot(snapshot: dict) -> None:
    """Validate investment snapshot has only finite position values.

    Args:
        snapshot: Snapshot dictionary with accounts.security/crypto structure

    Raises:
        ValueError: If any position has non-finite shares or total_cost

    Constitution I: Reject NaN and Infinity to ensure financial correctness.
    Negative values are allowed (short positions, certain cost scenarios).
    """
    accounts_data = snapshot.get("accounts", {})

    for account_type in ["security", "crypto"]:
        accounts = accounts_data.get(account_type, {})

        for account_name, account in accounts.items():
            positions = account.get("positions", {})

            for ticker, position in positions.items():
                # Validate shares
                shares_raw = position.get("shares", "0")
                try:
                    shares = Decimal(str(shares_raw))
                except Exception as e:
                    raise ValueError(
                        f"Position '{ticker}' in account '{account_name}' has "
                        f"invalid shares value: {shares_raw}"
                    ) from e

                if not shares.is_finite():
                    raise ValueError(
                        f"Position '{ticker}' in account '{account_name}' has "
                        f"non-finite shares: {shares}"
                    )

                # Validate total_cost
                total_cost_raw = position.get("total_cost", "0")
                try:
                    total_cost = Decimal(str(total_cost_raw))
                except Exception as e:
                    raise ValueError(
                        f"Position '{ticker}' in account '{account_name}' has "
                        f"invalid total_cost value: {total_cost_raw}"
                    ) from e

                if not total_cost.is_finite():
                    raise ValueError(
                        f"Position '{ticker}' in account '{account_name}' has "
                        f"non-finite total_cost: {total_cost}"
                    )
