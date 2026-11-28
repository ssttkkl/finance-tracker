#include <sqlite3ext.h>
SQLITE_EXTENSION_INIT1

static void ciphertext_chunk(sqlite3_context *context, int argc, sqlite3_value **argv) {
    (void)argc;
    sqlite3_blob *blob = 0;
    int offset = sqlite3_value_int(argv[1]);
    int length = sqlite3_value_int(argv[2]);
    if (offset < 0 || length <= 0 || length > 256 * 1024) {
        sqlite3_result_error(context, "Invalid ciphertext chunk", -1);
        return;
    }
    sqlite3 *connection = sqlite3_context_db_handle(context);
    int status = sqlite3_blob_open(connection, "main", "client_state", "payload",
        sqlite3_value_int64(argv[0]), 0, &blob);
    if (status != SQLITE_OK) {
        sqlite3_result_error_code(context, status);
        return;
    }
    void *buffer = sqlite3_malloc(length);
    if (!buffer) {
        sqlite3_blob_close(blob);
        sqlite3_result_error_nomem(context);
        return;
    }
    status = sqlite3_blob_read(blob, buffer, length, offset);
    sqlite3_blob_close(blob);
    if (status != SQLITE_OK) {
        sqlite3_free(buffer);
        sqlite3_result_error_code(context, status);
        return;
    }
    sqlite3_result_blob(context, buffer, length, sqlite3_free);
}

int sqlite3_ftciphertext_init(sqlite3 *database, char **error, const sqlite3_api_routines *api) {
    (void)error;
    SQLITE_EXTENSION_INIT2(api);
    return sqlite3_create_function(database, "ft_ciphertext_chunk", 3,
        SQLITE_UTF8 | SQLITE_DIRECTONLY, 0, ciphertext_chunk, 0, 0);
}
