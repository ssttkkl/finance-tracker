"""Transaction relations domain — physically split by kind (008)."""
from __future__ import annotations
from ft.domain.relations.core.types import *  # noqa: F403
from ft.domain.relations.core.keys import *  # noqa: F403
from ft.domain.relations.core.geometry import *  # noqa: F403
from ft.domain.relations.core.compatibility import *  # noqa: F403
from ft.domain.relations.core.projection import *  # noqa: F403
from ft.domain.relations.core.routing import source_group  # noqa: F401
from ft.domain.relations.core.record_types import *  # noqa: F403
from ft.domain.relations.core.types import MatchContext, RelationEdge  # noqa: F401
from ft.domain.relations.core.mirror_graph import (
    build_mirror_components, canonical_mirror_fact, platform_score as _platform_score,
)
from ft.domain.relations.mirror.match import (
    evaluate_payment_mirror, match_payment_mirrors_greedy,
)
from ft.domain.relations.transfer.match import (
    evaluate_transfer_pair, match_personal_fx_exchange, match_transfer_pairs_phase_c,
    match_normalized_subtype_transfers,
)
from ft.domain.relations.refund.signals import *  # noqa: F403
# DefaultRefundTextGates included via signals star export if named
from ft.domain.relations.refund.match import evaluate_refund_offset
from ft.domain.relations.refund.diamond import match_diamond_bank_refunds
from ft.domain.relations.refund.hard_key import match_phase_a_platform_refunds
from ft.domain.relations import pipeline as pipeline
from ft.domain.relations.pipeline import (
    match_canonical_payment_mirrors,
    run_relation_phases,
)
