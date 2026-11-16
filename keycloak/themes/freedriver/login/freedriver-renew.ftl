<#-- Sign-in renewal (#552). freedriverSignInLifetime is the realm's login lifespan in seconds
     (accessCodeLifespanLogin, set from ACCESS_CODE_LIFESPAN_LOGIN in
     scripts/provision-keycloak-freedriver.sh). After half of it the page starts a fresh sign-in at
     the dashboard: with JavaScript through sign-in-renew.js, without it through a refresh. -->
<#macro renew>
    <#if freedriverSignInLifetime??>
        <#assign renewAfter = (freedriverSignInLifetime / 2)?floor>
        <noscript><meta http-equiv="refresh" content="${renewAfter?c};url=https://app.freedriver.io/"></noscript>
        <script src="${url.resourcesPath}/js/sign-in-renew.js" data-renew-after-seconds="${renewAfter?c}" data-restart-url="https://app.freedriver.io/"></script>
    </#if>
</#macro>
