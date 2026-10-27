/**
 * Banking's screens (Phase 11S). Requires its feature and the screen contract
 * only; the shell finds it through {@code ScreenProvider} and never requires it.
 */
module accubook.ui.banking {
    requires accubook.ui.api;
    requires accubook.banking;
    requires accubook.accounting;

    provides com.accubook.ui.api.ScreenProvider with com.accubook.ui.banking.BankingScreens;
}
