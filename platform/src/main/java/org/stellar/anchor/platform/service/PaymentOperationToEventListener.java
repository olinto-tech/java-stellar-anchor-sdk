package org.stellar.anchor.platform.service;

import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.formatAmount;

import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.commons.codec.DecoderException;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.platform.PatchTransactionRequest;
import org.stellar.anchor.api.platform.PatchTransactionsRequest;
import org.stellar.anchor.api.platform.PlatformTransactionData;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.api.shared.StellarPayment;
import org.stellar.anchor.api.shared.StellarTransaction;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.platform.observer.ObservedPayment;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.xdr.MemoType;

public class PaymentOperationToEventListener implements PaymentListener {
  final JdbcSep31TransactionStore sep31TransactionStore;

  final JdbcSep24TransactionStore sep24TransactionStore;
  final JdbcSep6TransactionStore sep6TransactionStore;
  private final PlatformApiClient platformApiClient;

  public PaymentOperationToEventListener(
      JdbcSep31TransactionStore sep31TransactionStore,
      JdbcSep24TransactionStore sep24TransactionStore,
      JdbcSep6TransactionStore sep6TransactionStore,
      PlatformApiClient platformApiClient) {
    this.sep31TransactionStore = sep31TransactionStore;
    this.sep24TransactionStore = sep24TransactionStore;
    this.sep6TransactionStore = sep6TransactionStore;
    this.platformApiClient = platformApiClient;
  }

  @Override
  public void onReceived(ObservedPayment payment) throws IOException {
    // Check if payment is connected to a transaction
    if (Objects.toString(payment.getTransactionHash(), "").isEmpty()
        || Objects.toString(payment.getTransactionMemo(), "").isEmpty()) {
      traceF("Ignore the payment {} is not connected to a transaction.", payment.getId());
      return;
    }

    // Check if the payment contains the expected asset type
    if (!List.of("credit_alphanum4", "credit_alphanum12", "native")
        .contains(payment.getAssetType())) {
      // Asset type does not match
      debugF("{} is not an issued asset.", payment.getAssetType());
      return;
    }

    // Parse memo
    String memo = payment.getTransactionMemo();
    String memoType = payment.getTransactionMemoType();
    if (memoType.equals(MemoHelper.memoTypeAsString(MemoType.MEMO_HASH))) {
      try {
        memo = MemoHelper.convertHexToBase64(payment.getTransactionMemo());
      } catch (DecoderException ex) {
        infoF(
            "The memo type is \"hash\" but the memo string {} could not be parsed as such.", memo);
      }
    }

    // Find a transaction matching the memo, assumes transactions are unique to account+memo
    JdbcSep31Transaction sep31Txn = null;
    try {
      sep31Txn =
          sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(
              payment.getTo(), memo, SepTransactionStatus.PENDING_SENDER.toString());
    } catch (Exception ex) {
      errorEx(ex);
    }
    if (sep31Txn != null) {
      try {
        handleSep31Transaction(payment, sep31Txn);
        return;
      } catch (AnchorException aex) {
        warnF("Error handling the SEP31 transaction id={}.", sep31Txn.getId());
        errorEx(aex);
        return;
      }
    }

    // Find a transaction matching the memo, assumes transactions are unique to account+memo
    JdbcSep24Transaction sep24Txn;
    try {
      sep24Txn =
          sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
              payment.getTo(), memo, SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
    } catch (Exception ex) {
      errorEx(ex);
      return;
    }
    if (sep24Txn != null) {
      try {
        handleSep24Transaction(payment, sep24Txn);
      } catch (AnchorException aex) {
        warnF("Error handling the SEP24 transaction id={}.", sep24Txn.getId());
        errorEx(aex);
      }
    }

    // Find a transaction matching the memo, assumes transactions are unique to account+memo
    JdbcSep6Transaction sep6Txn;
    try {
      sep6Txn =
          sep6TransactionStore.findOneByToAccountAndMemoAndStatus(
              payment.getTo(), memo, SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
    } catch (Exception ex) {
      errorEx(ex);
      return;
    }
    if (sep6Txn != null) {
      try {
        handleSep6Transaction(payment, sep6Txn);
      } catch (AnchorException aex) {
        warnF("Error handling the SEP6 transaction id={}.", sep6Txn.getId());
        errorEx(aex);
      }
    }
  }

  @Override
  public void onSent(ObservedPayment payment) {
    // not implemented. NOOP.
  }

  void handleSep31Transaction(ObservedPayment payment, JdbcSep31Transaction txn)
      throws AnchorException, IOException {
    // Compare asset code
    String paymentAssetName = "stellar:" + payment.getAssetName();
    if (!txn.getAmountInAsset().equals(paymentAssetName)) {
      warnF(
          "Payment asset {} does not match the expected asset {}.",
          payment.getAssetCode(),
          txn.getAmountInAsset());
      return;
    }

    // parse payment creation time
    StellarTransaction stellarTransaction =
        buildStellarTransaction(payment, txn.getStellarMemo(), txn.getStellarMemoType());

    // Check if the payment contains the expected amount (or greater)
    BigDecimal expectedAmount = decimal(txn.getAmountIn());
    BigDecimal gotAmount = decimal(payment.getAmount());
    String message = "Incoming payment for SEP-31 transaction";
    if (gotAmount.compareTo(expectedAmount) >= 0) {
      Log.info(message);
      txn.setTransferReceivedAt(stellarTransaction.getCreatedAt());
    } else {
      message =
          String.format(
              "The incoming payment amount was insufficient! Expected: \"%s\", Received: \"%s\"",
              formatAmount(expectedAmount), formatAmount(gotAmount));
      Log.warn(message);
    }

    // TODO: this should be taken care of by the RPC actions.
    patchTransaction(txn, stellarTransaction, SepTransactionStatus.PENDING_RECEIVER);

    // Update metrics
    Metrics.counter(
            AnchorMetrics.SEP31_TRANSACTION.toString(),
            "status",
            SepTransactionStatus.PENDING_RECEIVER.toString())
        .increment();
    Metrics.counter(AnchorMetrics.PAYMENT_RECEIVED.toString(), "asset", payment.getAssetName())
        .increment(Double.parseDouble(payment.getAmount()));
  }

  void handleSep24Transaction(ObservedPayment payment, JdbcSep24Transaction txn)
      throws AnchorException, IOException {
    // Compare asset code
    String paymentAssetName = "stellar:" + payment.getAssetName();
    String txnAssetName = "stellar:" + txn.getRequestAssetName();
    if (!txnAssetName.equals(paymentAssetName)) {
      warnF(
          "Payment asset {} does not match the expected asset {}.",
          payment.getAssetCode(),
          txn.getAmountInAsset());
      return;
    }

    StellarTransaction stellarTransaction =
        buildStellarTransaction(payment, txn.getMemo(), txn.getMemoType());

    // Check if the payment contains the expected amount (or greater)
    BigDecimal amountIn = decimal(txn.getAmountIn());
    BigDecimal gotAmount = decimal(payment.getAmount());
    String message = "Incoming payment for SEP-24 transaction";
    if (gotAmount.compareTo(amountIn) == 0) {
      Log.info(message);
      txn.setTransferReceivedAt(stellarTransaction.getCreatedAt());
    } else {
      message =
          String.format(
              "The incoming payment amount was insufficient! Expected: \"%s\", Received: \"%s\"",
              formatAmount(amountIn), formatAmount(gotAmount));
      Log.warn(message);
    }

    // TODO: this should be taken care of by the RPC actions.
    patchTransaction(txn, stellarTransaction, SepTransactionStatus.PENDING_ANCHOR);
  }

  void handleSep6Transaction(ObservedPayment payment, JdbcSep6Transaction txn)
      throws AnchorException, IOException {
    if (!payment.getAssetName().equals(txn.getRequestAssetName())) {
      warnF(
          "Payment asset {} does not match the expected asset {}.",
          payment.getAssetCode(),
          txn.getRequestAssetName());
      return;
    }

    StellarTransaction stellarTransaction =
        buildStellarTransaction(payment, txn.getMemo(), txn.getMemoType());

    BigDecimal amountExpected = decimal(txn.getAmountExpected());
    BigDecimal gotAmount = decimal(payment.getAmount());
    if (gotAmount.compareTo(amountExpected) >= 0) {
      Log.infoF("Incoming payment for SEP-6 transaction {}.", txn.getId());
      txn.setTransferReceivedAt(stellarTransaction.getCreatedAt());
    } else {
      Log.warnF(
          "The incoming payment amount for SEP-6 transaction {} was insufficient! Expected: \"{}\", Received: \"{}\"",
          txn.getId(),
          formatAmount(amountExpected),
          formatAmount(gotAmount));
    }

    // TODO: this should be taken care of by the RPC actions.
    patchTransaction(txn, stellarTransaction, SepTransactionStatus.PENDING_ANCHOR);
  }

  private void patchTransaction(
      JdbcSepTransaction txn, StellarTransaction stellarTransaction, SepTransactionStatus newStatus)
      throws IOException, AnchorException {
    PatchTransactionsRequest patchTransactionsRequest =
        PatchTransactionsRequest.builder()
            .records(
                Collections.singletonList(
                    PatchTransactionRequest.builder()
                        .transaction(
                            PlatformTransactionData.builder()
                                .updatedAt(stellarTransaction.getCreatedAt())
                                .transferReceivedAt(txn.getTransferReceivedAt())
                                .status(newStatus)
                                .stellarTransactions(
                                    StellarTransaction.addOrUpdateTransactions(
                                        txn.getStellarTransactions(), stellarTransaction))
                                .id(txn.getId())
                                .build())
                        .build()))
            .build();
    debugF("Patching transaction {}.", txn.getId());
    traceF("Patching transaction {} with request {}.", txn.getId(), patchTransactionsRequest);
    platformApiClient.patchTransaction(patchTransactionsRequest);
  }

  StellarTransaction buildStellarTransaction(
      ObservedPayment payment, String memo, String memoType) {
    Instant paymentTime = parsePaymentTime(payment.getCreatedAt());
    return StellarTransaction.builder()
        .id(payment.getTransactionHash())
        .memo(memo)
        .memoType(memoType)
        .createdAt(paymentTime)
        .envelope(payment.getTransactionEnvelope())
        .payments(
            List.of(
                StellarPayment.builder()
                    .id(payment.getId())
                    .paymentType(
                        payment.getType() == ObservedPayment.Type.PAYMENT
                            ? StellarPayment.Type.PAYMENT
                            : StellarPayment.Type.PATH_PAYMENT)
                    .sourceAccount(payment.getFrom())
                    .destinationAccount(payment.getTo())
                    .amount(new Amount(payment.getAmount(), payment.getAssetName()))
                    .build()))
        .build();
  }

  Instant parsePaymentTime(String paymentTimeStr) {
    try {
      return DateTimeFormatter.ISO_INSTANT.parse(paymentTimeStr, Instant::from);
    } catch (DateTimeParseException | NullPointerException ex) {
      Log.errorF("Error parsing paymentTimeStr {}.", paymentTimeStr);
      ex.printStackTrace();
      return null;
    }
  }
}
