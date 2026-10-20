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
            <@buttons.actionGroup>
                <@buttons.button id="freedriver-sms-send" name="send" label="freedriverSmsSend" class=["kcButtonPrimaryClass", "kcButtonBlockClass"] />
            </@buttons.actionGroup>
        </form>
        <div id="freedriver-sms-password" class="${properties.kcFormGroupClass!}">
            <a class="${properties.kcButtonLinkClass!}" href="${url.loginRestartFlowUrl}">${msg("freedriverSmsUsePassword")}</a>
        </div>
    </#if>
</@layout.registrationLayout>
