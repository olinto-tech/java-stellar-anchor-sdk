package org.stellar.reference.dao

import org.jetbrains.exposed.sql.Table

/** Exposed table for the TransactionKYC model. */
object TransactionKYCs : Table() {
  val transactionId = text("transaction_id").uniqueIndex()
  val customerId = text("customer_id").index().nullable()
  val requiredFields = text("required_fields").nullable()
}
