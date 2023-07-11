package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.platform.config.PropertyCustodyConfig.TrustLine

class PropertyCustodyConfigTest {

  private lateinit var config: PropertyCustodyConfig
  private lateinit var errors: Errors

  @BeforeEach
  fun setUp() {
    config = PropertyCustodyConfig()
    config.type = "custodyType"
    config.httpClient = HttpClientConfig(10, 30, 30, 60)
    config.trustLine = TrustLine("* * * * * *", 10, "testMessage")
    errors = BindException(config, "config")
  }

  @ParameterizedTest
  @ValueSource(strings = ["none", "fireblocks"])
  fun `test valid type`(type: String) {
    config.type = type
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty type`(type: String?) {
    config.type = type
    config.validate(config, errors)
    assertErrorCode(errors, "custody-type-empty")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid http_client_connect_timeout`(connectTimeout: Int) {
    config.httpClient.connectTimeout = connectTimeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid http_client_connect_timeout`(connectTimeout: Int) {
    config.httpClient.connectTimeout = connectTimeout
    config.validate(config, errors)
    assertErrorCode(errors, "custody-http-client-connect-timeout-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid http_client_read_timeout`(readTimeout: Int) {
    config.httpClient.readTimeout = readTimeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid http_client_read_timeout`(readTimeout: Int) {
    config.httpClient.readTimeout = readTimeout
    config.validate(config, errors)
    assertErrorCode(errors, "custody-http-client-read-timeout-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid http_client_write_timeout`(writeTimeout: Int) {
    config.httpClient.writeTimeout = writeTimeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid http_client_write_timeout`(writeTimeout: Int) {
    config.httpClient.writeTimeout = writeTimeout
    config.validate(config, errors)
    assertErrorCode(errors, "custody-http-client-write-timeout-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid http_client_call_timeout`(callTimeout: Int) {
    config.httpClient.callTimeout = callTimeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid http_client_call_timeout`(callTimeout: Int) {
    config.httpClient.callTimeout = callTimeout
    config.validate(config, errors)
    assertErrorCode(errors, "custody-http-client-call-timeout-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid http_client none type`(timeout: Int) {
    config.type = "none"
    config.httpClient.connectTimeout = timeout
    config.httpClient.readTimeout = timeout
    config.httpClient.writeTimeout = timeout
    config.httpClient.callTimeout = timeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = ["* * * * * *", "0 0/15 * * * *"])
  fun `test valid trust_line_check_cron`(cron: String) {
    config.trustLine.checkCronExpression = cron
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  fun `test empty trust_line_check_cron`(cron: String) {
    config.trustLine.checkCronExpression = cron
    config.validate(config, errors)
    assertErrorCode(errors, "custody-trust_line-check_cron_expression-empty")
  }

  @ParameterizedTest
  @ValueSource(strings = ["* * * * *", "* * * * * * *", "0/a * * * * *"])
  fun `test invalid trust_line_check_cron`(cron: String) {
    config.trustLine.checkCronExpression = cron
    config.validate(config, errors)
    assertErrorCode(errors, "custody-trust_line-check_cron_expression-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [1, Int.MAX_VALUE])
  fun `test valid trust_line_check_duration`(duration: Int) {
    config.trustLine.checkDuration = duration
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [0, -1, Int.MIN_VALUE])
  fun `test invalid trust_line_check_duration`(duration: Int) {
    config.trustLine.checkDuration = duration
    config.validate(config, errors)
    assertErrorCode(errors, "custody-trust_line-check_duration-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid trust_line none type`(timeout: Int) {
    config.type = "none"
    config.trustLine.checkDuration = timeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }
}
