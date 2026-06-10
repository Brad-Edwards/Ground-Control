"""Phase-4 argument-map structural checks.

A: ungrounded premises (no {evidence:} tag, not derived elsewhere).
B: unreconstructed support (argument used as support, no PCS).
C: unanswered objections (attack on a load-bearing claim, no rebuttal).
D: circular support (a statement transitively supports itself).

Material validity (do the premises entail the conclusion) is delegated to
argdown_feedback's LogRecoCompositeHandler when --logreco is passed and
the PCS members carry {formalization:} metadata.
"""

from .evidence_grounding import check_evidence_grounding
from .unreconstructed_support import check_unreconstructed_support
from .unanswered_objection import check_unanswered_objection
from .circular_support import check_circular_support

__all__ = [
    "check_evidence_grounding",
    "check_unreconstructed_support",
    "check_unanswered_objection",
    "check_circular_support",
]
