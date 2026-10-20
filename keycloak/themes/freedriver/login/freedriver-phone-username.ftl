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
            <@buttons.actionGroup>
                <@buttons.button id="freedriver-phone-username-continue" name="continue" label="freedriverPhoneUsernameContinue" class=["kcButtonPrimaryClass", "kcButtonBlockClass"] />
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
