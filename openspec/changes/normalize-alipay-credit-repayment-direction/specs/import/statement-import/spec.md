## ADDED Requirements

### Requirement: Alipay credit repayment direction follows the funding accounts

When an Alipay `信用借还` business row has source direction `不计收支` and passes the existing failed-repayment filter, the system MUST normalize every nonzero cash amount as an outflow from its mapped funding account. The normalized amount MUST be the exact negative `Decimal` magnitude from the source row. The system MUST preserve the original source row and positive source amount in `source_payload`, and MUST retain `record_type=repayment` with `record_subtype=credit_repayment`. A zero amount MUST remain zero. Other `不计收支` business types MUST retain their existing source-specific direction rules.

#### Scenario: Successful non-expense credit repayment

- **WHEN** an Alipay `信用借还` row is marked `不计收支`, has a nonzero source amount, and is not filtered as a failed repayment
- **THEN** the normalized cash amount MUST be negative with the same exact `Decimal` magnitude
- **AND** the original positive amount, direction, status, and payment-method fields MUST remain unchanged in the source snapshot
- **AND** the row MUST retain `record_type=repayment` and `record_subtype=credit_repayment`

#### Scenario: Zero-value credit repayment

- **WHEN** an Alipay `信用借还` row is marked `不计收支` and its source amount is zero
- **THEN** the normalized cash amount MUST remain zero
- **AND** the row MUST retain the source snapshot and repayment classification

#### Scenario: Confirmed composite credit repayment allocation

- **WHEN** a user confirms exact component magnitudes for an Alipay credit repayment funded by multiple mapped accounts
- **THEN** every component amount MUST be negative
- **AND** the exact `Decimal` sum of component amounts MUST equal the normalized parent amount
- **AND** the confirmed component magnitudes and mapped accounts MUST be preserved

#### Scenario: Other non-expense transaction direction

- **WHEN** an Alipay business row is marked `不计收支` but its transaction type is not `信用借还`
- **THEN** the system MUST apply that transaction type's existing direction rule without treating it as a credit repayment
