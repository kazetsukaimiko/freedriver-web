package io.freedriver.sms.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Body of {@code POST /sms/send}. The {@code type} property picks the subtype; a missing or
 * unknown type is a 400.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(@JsonSubTypes.Type(value = OtpSendRequest.class, name = SendType.OTP_NAME))
public sealed interface SendRequest permits OtpSendRequest {

    SendType type();
}
