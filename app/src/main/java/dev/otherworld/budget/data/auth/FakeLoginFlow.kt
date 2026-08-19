package dev.otherworld.budget.data.auth

/** Used by Compose previews and unit tests; never registered in the release graph. */
class FakeLoginFlow(
    private val start: Result<LoginFlowStart>,
    private val poll: Result<Credentials>,
) : LoginFlow {
    override suspend fun start(serverUrl: String): Result<LoginFlowStart> = start
    override suspend fun poll(start: LoginFlowStart): Result<Credentials> = poll
}
