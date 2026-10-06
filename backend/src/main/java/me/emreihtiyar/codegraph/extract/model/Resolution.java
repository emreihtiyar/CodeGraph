package me.emreihtiyar.codegraph.extract.model;

/** Bir bağın hedefinin ne kadar kesin bulunduğu (PRD FR-05, FR-09). */
public enum Resolution {
    RESOLVED,
    /** Birden çok aday var (ör. bir arayüzün birden çok implementasyonu); her adaya ayrı kenar açılır. */
    AMBIGUOUS,
    /** Hedef bulunamadı; bağ silinmez, raporlanır. */
    UNRESOLVED
}
