<#-- Sign-in renewal (#552). freedriverSignInLifetime is the realm's login lifespan in seconds
     (accessCodeLifespanLogin, set from ACCESS_CODE_LIFESPAN_LOGIN in
     scripts/provision-keycloak-freedriver.sh). A page renews only for a client with a home address
     (its baseUrl, which the provision script sets on freedriver-api alone), and it goes to that
     address: after half the lifetime with JavaScript through sign-in-renew.js, without it through a
     refresh. A submit is stopped only after the full lifetime, when Keycloak's session has ended. -->
<#macro renew phonePatternLoaded=false>
    <#if freedriverSignInLifetime?? && client?? && client.baseUrl?has_content>
        <#assign renewAfter = (freedriverSignInLifetime / 2)?floor>
        <noscript><meta http-equiv="refresh" content="${renewAfter?c};url=${client.baseUrl}"></noscript>
        <#if !phonePatternLoaded>
        <script src="${url.resourcesPath}/js/us-phone.js"></script>
        </#if>
        <script src="${url.resourcesPath}/js/sign-in-renew.js" data-renew-after-seconds="${renewAfter?c}" data-lifetime-seconds="${freedriverSignInLifetime?c}" data-restart-url="${client.baseUrl}"></script>
    </#if>
</#macro>
