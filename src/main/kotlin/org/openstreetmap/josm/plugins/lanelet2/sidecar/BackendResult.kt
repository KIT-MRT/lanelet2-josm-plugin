package org.openstreetmap.josm.plugins.lanelet2.sidecar

/**
 * Structured outcome of a backend subprocess. Failures are values, not
 * thrown exceptions — matching `backend_runner._run_backend_cmd`, which
 * returns `(success, error_str, stdout_text)` and only uses exceptions for
 * "could not even start the process".
 */
data class BackendResult(
    val success: Boolean,
    val error: String?,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
) {
    companion object {
        fun ok(stdout: String = "", stderr: String = "", exitCode: Int = 0): BackendResult =
            BackendResult(true, null, stdout, stderr, exitCode)

        fun fail(
            error: String,
            stdout: String = "",
            stderr: String = "",
            exitCode: Int? = null,
        ): BackendResult = BackendResult(false, error, stdout, stderr, exitCode)
    }
}
