<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout; section>
<!-- template: freedriver-phone-username.ftl -->
    <#if section = "header">
        ${msg("freedriverPhoneUsernameTitle")}
    <#elseif section = "form">
        <form id="freedriver-phone-username-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <@field.input name="username" label=msg("freedriverPhoneUsernameLabel") autocomplete="username" autofocus=true />
            <p id="freedriver-sms-agreement">${freedriverSmsAgreement}</p>
            <@buttons.actionGroup>
                <@buttons.button id="freedriver-phone-username-continue" name="continue" label="freedriverPhoneUsernameContinue" class=["kcButtonPrimaryClass", "kcButtonBlockClass"] />
            </@buttons.actionGroup>
            <script src="${url.resourcesPath}/js/phone-fragment.js"></script>
        </form>
    </#if>
</@layout.registrationLayout>
