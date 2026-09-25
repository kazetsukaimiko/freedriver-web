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
        <form id="freedriver-sms-more-form" class="${properties.kcFormGroupClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <#if freedriverSmsResendAllowed!false>
                <button type="submit" name="resend" value="1" class="${properties.kcButtonLinkClass!}">${msg("freedriverSmsResend")}</button>
            <#else>
                <span>${msg("freedriverSmsCodeLimit")}</span>
                <button type="submit" name="startOver" value="1" class="${properties.kcButtonLinkClass!}">${msg("freedriverSmsStartOver")}</button>
            </#if>
        </form>
        <div id="freedriver-sms-password" class="${properties.kcFormGroupClass!}">
            <a class="${properties.kcButtonLinkClass!}" href="${url.loginRestartFlowUrl}">${msg("freedriverSmsUsePassword")}</a>
        </div>
    </#if>
</@layout.registrationLayout>
