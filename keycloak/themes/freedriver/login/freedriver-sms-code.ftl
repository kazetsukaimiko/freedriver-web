<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout; section>
<!-- template: freedriver-sms-code.ftl -->
    <#if section = "header">
        ${msg("freedriverSmsTitle")}
    <#elseif section = "form">
        <p id="freedriver-sms-code-hint">${msg("freedriverSmsCodeHint")}</p>
        <form id="freedriver-sms-code-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <@field.input name="code" label=msg("freedriverSmsCodeLabel") autocomplete="one-time-code" autofocus=true />
            <@buttons.loginButton />
        </form>
        <#if freedriverSmsResendAllowed!false>
            <form id="freedriver-sms-more-form" class="${properties.kcFormGroupClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
                <button type="submit" name="resend" value="1" class="${properties.kcButtonLinkClass!}">${msg("freedriverSmsResend")}</button>
            </form>
        <#else>
            <p id="freedriver-sms-code-limit" class="${properties.kcFormGroupClass!}">${msg("freedriverSmsCodeLimit")}</p>
        </#if>
        <div id="freedriver-sms-password" class="${properties.kcFormGroupClass!}">
            <a class="${properties.kcButtonLinkClass!}" href="${url.loginRestartFlowUrl}">${msg("freedriverSmsUsePassword")}</a>
        </div>
    </#if>
</@layout.registrationLayout>
