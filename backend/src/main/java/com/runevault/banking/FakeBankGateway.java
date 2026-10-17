package com.runevault.banking;

import com.runevault.TransactionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "runevault.mode", havingValue = "local")
public class FakeBankGateway implements BankGateway {
    private final TransactionRepository transactions;
    private final FakeReceiptRepository receipts;
    private final TransactionTemplate tx;
    public FakeBankGateway(TransactionRepository transactions, FakeReceiptRepository receipts, PlatformTransactionManager manager) {
        this.transactions = transactions; this.receipts = receipts;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public BankReceipt transfer(String id, long amount, String currency, String destination) {
        if (id == null || id.isBlank() || amount <= 0 || !"USD".equals(currency) || destination == null || destination.isBlank())
            throw new BankRejectedException("Invalid simulated transfer details");
        // This gateway is entirely local. Serialize receipt insertion on the durable transaction row.
        return tx.execute(ignored -> {
            var transaction = transactions.findForUpdate(id).orElseThrow(() -> new BankRejectedException("Unknown transaction"));
            if (transaction.getAmountMinor() != amount || !transaction.getDestination().equals(destination))
                throw new BankRejectedException("Transaction ID belongs to different transfer details");
            var stored = receipts.findById(id).orElse(null);
            if (stored != null) {
                var receipt = stored.receipt();
                if (receipt.amountMinor() != amount || !receipt.currency().equals(currency) || !receipt.destination().equals(destination))
                    throw new BankRejectedException("Transaction ID belongs to different transfer details");
                return receipt;
            }
            return receipts.saveAndFlush(new FakeReceipt(id, amount, currency, destination)).receipt();
        });
    }
}
