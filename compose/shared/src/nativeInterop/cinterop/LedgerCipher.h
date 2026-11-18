#ifndef FINANCE_TRACKER_LEDGER_CIPHER_H
#define FINANCE_TRACKER_LEDGER_CIPHER_H

struct sqlite3;
int sqlite3_key(struct sqlite3 *database, const void *key, int key_length);

#endif
