<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout; section>
<!-- template: freedriver-sms-phone.ftl -->
    <#if section = "header">
        ${msg("freedriverSmsTitle")}
    <#elseif section = "form">
        <form id="freedriver-sms-phone-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <@field.input name="phone" label=msg("freedriverSmsPhoneLabel") autocomplete="tel" autofocus=true />
            <p id="freedriver-sms-agreement">${freedriverSmsAgreement}</p>
            <@buttons.actionGroup>
                <@buttons.button id="freedriver-sms-send" name="send" label="freedriverSmsSend" class=["kcButtonPrimaryClass", "kcButtonBlockClass"] />
            </@buttons.actionGroup>
        </form>
        <div id="freedriver-sms-password" class="${properties.kcFormGroupClass!}">
            <a class="${properties.kcButtonLinkClass!}" href="${url.loginRestartFlowUrl}">${msg("freedriverSmsUsePassword")}</a>
        </div>
        <#-- keycloak.v2 prints Try another way after this form when auth is present.
             This page's only alternate path is the password link. -->
        <#global auth="">
    </#if>
</@layout.registrationLayout>
