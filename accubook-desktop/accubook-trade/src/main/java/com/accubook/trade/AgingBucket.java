package com.accubook.trade;

/** P7-15: days past the due date (the document date when there is none). */
public enum AgingBucket {
    NOT_DUE, DAYS_1_30, DAYS_31_60, DAYS_61_90, OVER_90;

    public static AgingBucket of(long daysPastDue) {
        if (daysPastDue <= 0) {
            return NOT_DUE;
        }
        if (daysPastDue <= 30) {
            return DAYS_1_30;
        }
        if (daysPastDue <= 60) {
            return DAYS_31_60;
        }
        return daysPastDue <= 90 ? DAYS_61_90 : OVER_90;
    }
}
