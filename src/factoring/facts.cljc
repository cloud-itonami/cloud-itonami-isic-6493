(ns factoring.facts
  "Per-jurisdiction factoring/receivables-assignment regulatory catalog
  -- the G2-style spec-basis table the Factoring Governor checks every
  `:receivable/verify` proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's receivables-
  assignment/factoring legal-basis and required evidence, or did it
  invent one?'), the factoring analog of `credit.facts` (`cloud-
  itonami-isic-6492`).

  The governing jurisdiction for a receivable is the CLIENT's (the
  assignor/seller of the receivable) home jurisdiction -- this mirrors
  UCC Article 9's own choice-of-law rule (perfection of a security
  interest/sale of accounts follows the location of the 'debtor' in
  UCC terms, i.e. the ASSIGNOR granting/selling the interest, NOT the
  account debtor who owes payment on the underlying invoice). This is
  deliberate and distinct from the ACCOUNT DEBTOR's creditworthiness,
  which `factoring.governor`'s `debtor-concentration-ceiling-exceeded-
  violations` checks separately -- see that check's own docstring for
  why factoring inverts the credit-risk side relative to `credit.
  governor`'s (`6492`) borrower-side affordability check while keeping
  the LEGAL spec-basis on the assignor's (client's) side, same as UCC
  Article 9.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's real, verifiable legal
  basis for receivables assignment / factoring (see `:provenance`);
  they are a STARTING catalog, not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is additive: add one map to
  `catalog`, cite a real source -- never invent a jurisdiction's
  requirements to make coverage look bigger.

  Unlike `credit.facts`'s USA entry (a genuinely NATIONAL truth-in-
  lending regime under the CFPB), factoring's US legal basis (UCC
  Article 9) is enacted STATE-by-state -- but Article 9's substantive
  text is materially uniform across all 50 states (the very point of a
  'uniform' commercial code), so citing the Uniform Commercial Code
  text itself (via Cornell LII, the standard reference every US legal
  citation in this fleet uses) is honest without picking an arbitrary
  single state, unlike insurance/real-estate licensing (genuinely
  per-state, no uniform text) which sibling actors correctly modeled
  with a state exemplar (`USA-NY` etc). GBR's legal basis is UNUSUAL
  among this fleet's 4-jurisdiction seed: factoring itself is NOT a
  licensed/statutorily-regulated activity in the UK (unlike lending,
  insurance or real-estate agency) -- it is industry self-regulated via
  UK Finance's Invoice Finance and Asset Based Lending Standards
  Framework, with FCA authorisation only required at the narrow
  boundary where a financier acquires debts that are themselves
  regulated consumer-credit/consumer-hire agreements (Consumer Credit
  Act 1974). `:owner-authority` reflects this honestly (an industry
  body, not a statutory regulator) rather than fabricating a licensing
  regime that does not exist.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  invoice/assignment-notification/duplicate-pledge/KYC evidence set a
  factor collects before advancing cash against a receivable;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any `:receivable/verify`
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "法務省 (Ministry of Justice) -- 動産・債権譲渡登記制度"
          :legal-basis "民法第467条 (債権譲渡の対抗要件) / 動産及び債権の譲渡の対抗要件に関する民法の特例等に関する法律 (動産・債権譲渡特例法)"
          :national-spec "債権譲渡登記令・債権譲渡登記規則 (法務局 債権譲渡登記制度)"
          :provenance "https://laws.e-gov.go.jp/law/410AC0000000104/"
          :required-evidence ["請求書原本 (original invoice)"
                              "債権譲渡通知または債務者の承諾 (notification of assignment to, or acknowledgement by, the account debtor)"
                              "重複譲渡なしの確認 (no-duplicate-pledge confirmation)"
                              "譲渡人・債務者双方のKYC書類 (KYC documentation for both client and account debtor)"]}
   "USA" {:name "United States"
          :owner-authority "State Secretary of State UCC filing offices, under the Uniform Commercial Code"
          :legal-basis "Uniform Commercial Code Article 9 (Secured Transactions) §9-109(a)(3) (scope: a sale of accounts is within Article 9) / §9-310 (financing-statement filing to perfect)"
          :national-spec "UCC Article 9, enacted state-by-state with materially uniform substantive text"
          :provenance "https://www.law.cornell.edu/ucc/9"
          :required-evidence ["Original invoice"
                              "Notification of assignment to (or acknowledgement by) the account debtor"
                              "No-duplicate-pledge / lien search confirmation (UCC-1 financing statement search)"
                              "KYC documentation for both client and account debtor"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "UK Finance -- Invoice Finance and Asset Based Lending Standards Framework (industry self-regulation; FCA authorisation applies only at the narrow Consumer Credit Act 1974 boundary)"
          :legal-basis "Consumer Credit Act 1974 (scope boundary for factoring vs. regulated consumer credit) / FCA Handbook CONC (where a regulated agreement is acquired)"
          :national-spec "UK Finance Invoice Finance and Asset Based Lending Standards Framework"
          :provenance "https://www.ukfinance.org.uk/"
          :required-evidence ["Original invoice"
                              "Notification of assignment to (or acknowledgement by) the account debtor"
                              "No-duplicate-pledge confirmation"
                              "KYC documentation for both client and account debtor"]}
   "DEU" {:name "Germany"
          :owner-authority "kein branchenspezifischer Zulassungsträger für echtes Factoring (BaFin nur bei unechtem/kreditähnlichem Factoring, KWG §1 Abs. 1 Nr. 2)"
          :legal-basis "Bürgerliches Gesetzbuch (BGB) §398 (Abtretung) i.V.m. §433 (Kaufvertrag als Grundgeschäft)"
          :national-spec "BGB §398 ff. (Abtretung), §407 (Schutz des Schuldners bei Unkenntnis der Abtretung)"
          :provenance "https://www.gesetze-im-internet.de/bgb/__398.html"
          :required-evidence ["Rechnung im Original (original invoice)"
                              "Abtretungsanzeige an den Schuldner (notification of assignment to the account debtor)"
                              "Bestätigung: keine Mehrfachabtretung (no-duplicate-pledge confirmation)"
                              "KYC-Unterlagen für Kunde und Schuldner (KYC documentation for both client and account debtor)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to verify a
  receivable on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6493 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `factoring.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
