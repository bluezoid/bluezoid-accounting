package com.accubook.purchase;

import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.app.company.CompanyService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.stock.StockPort;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.RulePacks;
import com.accubook.trade.Document;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.DocumentStatus;
import com.accubook.trade.DraftRequest;
import com.accubook.trade.LineRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The module's use cases on a real company, in a build without inventory. */
@DisplayName("Purchase billing")
class PurchaseBillingIT {

    @Test
    @DisplayName("posts a free-text document through the module, discovered as a feature")
    void postsThroughModule(@TempDir Path dir) throws Exception {
        // This module's own tests have no inventory on the path: a BASIC-like build.
        assertThat(StockPort.installed()).isEmpty();

        try (CompanyService service = CompanyService.start(dir,
                ModuleRegistry.discover(List.of(new PurchaseBillingModule())), id -> ModuleState.ACTIVE)) {
            String companyId = service.createCompany("Asha Traders").id();
            service.openCompany(companyId);
            // P9-8: no document without a GST position. Unregistered: these tests predate GST on documents.
            new GstRegistrations(service.openCompanyTransactions(), Clock.systemUTC()).register(GstRegistration.Scheme.UNREGISTERED, null, "27",
                    LocalDate.of(2017, 7, 1), RulePacks.bundledPack());
            Party party = new Parties(service.openCompanyTransactions()).create("Mehta & Co", PartyRole.VENDOR, null, 0);
            PurchaseBilling module = new PurchaseBilling(service.openCompanyTransactions(),
                    new EntitlementGuard(service.moduleStates()));

            Document posted = module.post(module.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                    party.id(), LocalDate.of(2026, 5, 10),
                    List.of(LineRequest.text("Annual maintenance", new BigDecimal("1"), new BigDecimal("12000")))))
                    .id());

            assertThat(posted.status()).isEqualTo(DocumentStatus.POSTED);
            assertThat(posted.number()).isEqualTo("PB/26-27/00001");
            assertThat(module.list()).hasSize(1);
        }
    }
}
