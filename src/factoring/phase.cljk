(ns factoring.phase
  "Phase 0->3 staged rollout -- the factoring analog of `credit.phase`
  (`cloud-itonami-isic-6492`).

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- receivable intake allowed, every
                                 write needs human approval.
    Phase 2  assisted-verify  -- adds verification + underwriting
                                 writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:receivable/intake` (no capital
                                 risk yet) may auto-commit. Every
                                 other write, including the solvency
                                 attestation itself, always escalates.

  `:advance/fund` and `:reserve/settle` are deliberately ABSENT from
  every phase's `:auto` set, including phase 3 -- a permanent
  structural fact, not a rollout milestone still to come. Advancing
  real cash and releasing a reserve are the TWO real-world financial
  acts this actor performs; both are always a human factor's call.
  `factoring.governor`'s `:actuation/advance-cash`/`:actuation/
  release-reserve` high-stakes gate enforces the same invariant
  independently -- two layers, not one, agree on this.

  `:solvency/attest` is ALSO never auto-eligible, at any phase, even
  though it moves no capital -- like `credit.phase`'s treatment of
  `:loan/approve`, publishing a solvency attestation is a genuine
  DECISION with real reputational/legal consequences if wrong, not
  mere data normalization, so a human always reviews before it
  publishes -- the governor's own independent recompute (`factoring.
  governor/solvency-attestation-mismatch-violations`) guarantees the
  NUMBERS are correct, but this actor still does not let publishing
  the resulting public claim run unattended. `:receivable/verify`,
  `:receivable/underwrite` and `:receivable/collect` are likewise
  never auto-eligible -- each is a genuine decision/event-confirmation,
  the same posture every sibling actor's assessment-style ops have.
  Phase 3's `:auto` set here has only ONE member (`:receivable/
  intake`), the same shape `credit.phase`'s phase 3 has."
  )

(def read-ops  #{:audit/report})
(def write-ops #{:receivable/intake :receivable/verify :receivable/underwrite
                 :advance/fund :receivable/collect :reserve/settle :solvency/attest})

;; NOTE the invariant: `:advance/fund` and `:reserve/settle` are members
;; of `write-ops` (governor-gated like any write) but are NEVER members
;; of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                                :auto #{}}
   1 {:label "assisted-intake" :writes #{:receivable/intake}                                              :auto #{}}
   2 {:label "assisted-verify" :writes #{:receivable/intake :receivable/verify :receivable/underwrite}     :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:receivable/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads pass through unchanged (phase restricts autonomy, not reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:advance/fund`/`:reserve/settle`/`:solvency/attest` are never
    auto-eligible at any phase, so they always escalate once the
    governor clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        {:keys [writes auto]} (get phases p {:writes #{} :auto #{}})]
    (cond
      (contains? read-ops op)
      {:disposition governor-disposition :reason nil}

      (= governor-disposition :hold)
      {:disposition :hold :reason nil}

      (not (contains? writes op))
      {:disposition :hold :reason :phase-disabled}

      (and (= governor-disposition :commit) (not (contains? auto op)))
      {:disposition :escalate :reason :phase-approval}

      :else
      {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Factoring Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
