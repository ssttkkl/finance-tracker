"""Connector synchronization orchestration and idempotency."""
from ft.domain.application import ExportPayload
from ft.domain.sync import (
    ConnectorSyncResultDTO,
    external_event_id,
    row_identity,
)
from ft.schema import CSV_FIELDS


class ConnectorSyncService:
    def __init__(self, registry, secrets, mappings, events, change_sets):
        self._registry = registry
        self._secrets = secrets
        self._mappings = mappings
        self._events = events
        self._change_sets = change_sets

    def sync(self, command) -> ConnectorSyncResultDTO:
        self._events.validate_destination(command.provider, command.account)
        connector = self._registry.get_connector(command.provider)
        if command.provider == "polymarket" and (command.wallet or command.proxy_wallet):
            credentials = {}
        else:
            credentials = self._secrets.get_secret(command.provider, command.account)
        mapping = self._mappings.get_mapping(f"sync:{command.provider}")
        fetched = connector.fetch(command, credentials=credentials, mapping=mapping)
        external_ids, exact_rows = self._events.existing_identities(
            command.provider, command.account
        )
        new_rows, skipped = self._filter_rows(fetched, external_ids, exact_rows)

        enrich = getattr(connector, "enrich", None)
        if callable(enrich):
            enriched = enrich(
                command, tuple(new_rows), credentials=credentials, mapping=mapping
            )
            extra_rows, extra_skipped = self._filter_rows(
                enriched,
                external_ids | {
                    event_id for row in new_rows
                    if (event_id := external_event_id(row)) is not None
                },
                exact_rows | {row_identity(row) for row in new_rows},
            )
            new_rows.extend(extra_rows)
            skipped += extra_skipped
            fetched = [*fetched, *enriched]

        new_rows.sort(key=lambda row: row.get("date", ""))
        export = None
        if command.export:
            export = ExportPayload(tuple(new_rows), fieldnames=tuple(CSV_FIELDS))
        if new_rows and not command.dry_run and not command.export:
            self._events.append_events(new_rows)
            self._change_sets.stage()
        return ConnectorSyncResultDTO(
            provider=command.provider,
            account=command.account,
            fetched_count=len(fetched),
            new_count=len(new_rows),
            skipped_count=skipped,
            rows=tuple(new_rows),
            export=export,
        )

    @staticmethod
    def _filter_rows(rows, existing_ids, existing_exact):
        new_rows = []
        skipped = 0
        seen_ids = set(existing_ids)
        seen_exact = set(existing_exact)
        for row in rows:
            event_id = external_event_id(row)
            identity = row_identity(row)
            if (event_id and event_id in seen_ids) or identity in seen_exact:
                skipped += 1
                continue
            new_rows.append(dict(row))
            if event_id:
                seen_ids.add(event_id)
            seen_exact.add(identity)
        return new_rows, skipped


__all__ = ["ConnectorSyncService", "row_identity"]
