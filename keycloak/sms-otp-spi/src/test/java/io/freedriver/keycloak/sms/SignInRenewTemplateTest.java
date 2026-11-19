package io.freedriver.keycloak.sms;

import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.StringTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.core.HTMLOutputFormat;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateMethodModelEx;
import io.freedriver.sms.validation.UsPhoneNumber;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sign-in renewal (#552) as Keycloak renders it: freedriver-renew.ftl with the realm's login lifespan
 * and the client of the sign-in. A client with a home address (its baseUrl) gets the refresh and the
 * script, both pointing at that address; a client without one gets neither.
 */
class SignInRenewTemplateTest {

    /** Surefire's working directory is this module. */
    private static final Path THEME = Path.of("src/main/resources/theme/freedriver/login");

    /** The client bean Keycloak puts in the template; only its home address matters here. */
    public static final class Client {
        private final String baseUrl;

        Client(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getBaseUrl() {
            return baseUrl;
        }
    }

    @Test
    void aClientWithAHomeAddressRenewsToThatAddress() throws Exception {
        String page = render(7200, new Client("https://app.freedriver.io/"));
        assertTrue(page.contains("<noscript><meta http-equiv=\"refresh\" content=\"3600;url=https://app.freedriver.io/\"></noscript>"), page);
        assertTrue(page.contains("src=\"/resources/x/login/freedriver/js/sign-in-renew.js\""), page);
        assertTrue(page.contains("data-renew-after-seconds=\"3600\""), page);
        assertTrue(page.contains("data-lifetime-seconds=\"7200\""), page);
        assertTrue(page.contains("data-restart-url=\"https://app.freedriver.io/\""), page);
    }

    @Test
    void aClientWithoutAHomeAddressGetsNoRenewal() throws Exception {
        for (Client client : new Client[] {new Client(null), new Client(""), null}) {
            String page = render(7200, client);
            assertFalse(page.contains("refresh"), page);
            assertFalse(page.contains("sign-in-renew.js"), page);
        }
    }

    @Test
    void theAddressIsTheClientsNotTheThemes() throws Exception {
        String page = render(600, new Client("https://grafana.example/"));
        assertTrue(page.contains("content=\"300;url=https://grafana.example/\""), page);
        assertTrue(page.contains("data-restart-url=\"https://grafana.example/\""), page);
        assertFalse(Files.readString(THEME.resolve("freedriver-renew.ftl")).contains("https://"),
                "freedriver-renew.ftl names no address");
    }

    @Test
    void bothSignInPagesBeforeACodeIncludeTheRenewal() throws IOException {
        for (String page : List.of("freedriver-phone-username.ftl", "freedriver-sms-phone.ftl")) {
            String form = Files.readString(THEME.resolve(page));
            assertTrue(form.contains("<#import \"freedriver-renew.ftl\" as renewal>"), page);
            assertTrue(form.contains("<@renewal.renew"), page);
        }
    }

    @Test
    void storedUsPhoneScriptMatchesUsPhoneNumber() throws IOException {
        String script = Files.readString(THEME.resolve("resources/js/us-phone.js"));
        Matcher literal = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(script);
        assertTrue(literal.find(), script);
        String pattern = unescapeJava(literal.group(1));
        assertFalse(literal.find(), "us-phone.js holds one pattern string");
        assertEquals(UsPhoneNumber.REGEX, pattern);
        assertTrue(Pattern.compile(pattern).matcher("+15555550100").matches());
        assertFalse(Pattern.compile(pattern).matcher("+447700900123").matches());
        assertFalse(script.contains("location.hash"), script);
        assertFalse(script.contains("sessionStorage"), script);
        for (String caller : List.of("phone-fragment.js", "sign-in-renew.js")) {
            String source = Files.readString(THEME.resolve("resources/js").resolve(caller));
            assertFalse(source.contains("[0-9]{10}"), caller);
            assertFalse(source.contains("/^"), caller);
            assertTrue(source.contains("freedriverStoredUsPhone"), caller);
        }
        for (String page : List.of("freedriver-phone-username.ftl", "freedriver-renew.ftl")) {
            assertTrue(Files.readString(THEME.resolve(page)).contains("${url.resourcesPath}/js/us-phone.js"), page);
        }
    }

    @Test
    void theRouterAndSmsPagesLoadTheSharedScriptOnceBeforeItsCaller() throws Exception {
        String withHome = renderPage("freedriver-phone-username.ftl", "https://app.freedriver.io/");
        assertTrue(loadsSharedScriptOnceBefore(withHome, "phone-fragment.js"), withHome);
        assertTrue(loadsSharedScriptOnceBefore(withHome, "sign-in-renew.js"), withHome);

        String withoutHome = renderPage("freedriver-phone-username.ftl", null);
        assertTrue(loadsSharedScriptOnceBefore(withoutHome, "phone-fragment.js"), withoutHome);
        assertFalse(withoutHome.contains("sign-in-renew.js"), withoutHome);

        String sms = renderPage("freedriver-sms-phone.ftl", "https://app.freedriver.io/");
        assertTrue(loadsSharedScriptOnceBefore(sms, "sign-in-renew.js"), sms);
        assertFalse(sms.contains("phone-fragment.js"), sms);
    }

    @Test
    void aCallerBeforeTheSharedScriptFails() {
        String bad = "<script src=\"/js/phone-fragment.js\"></script><script src=\"/js/us-phone.js\"></script>";
        assertFalse(loadsSharedScriptOnceBefore(bad, "phone-fragment.js"));
    }

    private static String render(int lifetimeSeconds, Client client) throws IOException, TemplateException {
        // Keycloak's DefaultFreeMarkerProvider: 2.3.32 rules and HTML output.
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setOutputFormat(HTMLOutputFormat.INSTANCE);
        cfg.setDirectoryForTemplateLoading(THEME.toFile());
        Map<String, Object> model = new HashMap<>();
        model.put(SignInLifetime.ATTR, lifetimeSeconds);
        model.put("url", Map.of("resourcesPath", "/resources/x/login/freedriver"));
        if (client != null) {
            model.put("client", client);
        }
        Template page = new Template("page.ftl",
                new StringReader("<#import \"freedriver-renew.ftl\" as renewal><@renewal.renew/>"), cfg);
        StringWriter out = new StringWriter();
        page.process(model, out);
        return out.toString();
    }

    private static String renderPage(String template, String home) throws IOException, TemplateException {
        StringTemplateLoader stubs = new StringTemplateLoader();
        stubs.putTemplate("template.ftl", "<#macro registrationLayout><#nested \"form\"></#macro>");
        stubs.putTemplate("field.ftl",
                "<#macro input name label autocomplete=\"\" autofocus=false><input name=\"${name}\"/></#macro>");
        stubs.putTemplate("buttons.ftl",
                "<#macro actionGroup><#nested></#macro>"
                        + "<#macro button id name label class=[]><button id=\"${id}\" name=\"${name}\"></button></#macro>");
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setOutputFormat(HTMLOutputFormat.INSTANCE);
        cfg.setTemplateLoader(new MultiTemplateLoader(new TemplateLoader[] {
                stubs,
                new FileTemplateLoader(THEME.toFile())
        }));
        Map<String, Object> model = new HashMap<>();
        model.put(SignInLifetime.ATTR, 7200);
        model.put("url", Map.of(
                "resourcesPath", "/resources/x/login/freedriver",
                "loginAction", "/login",
                "loginRestartFlowUrl", "/restart"));
        model.put("properties", Map.of());
        model.put("msg", (TemplateMethodModelEx) arguments -> String.valueOf(arguments.get(0)));
        model.put("freedriverSmsAgreement", "agreement");
        if (home != null) {
            model.put("client", new Client(home));
        }
        StringWriter out = new StringWriter();
        cfg.getTemplate(template).process(model, out);
        return out.toString();
    }

    static boolean loadsSharedScriptOnceBefore(String page, String caller) {
        String shared = "/js/us-phone.js";
        int first = page.indexOf(shared);
        if (first < 0 || page.indexOf(shared, first + shared.length()) >= 0) {
            return false;
        }
        return page.indexOf(caller) > first;
    }

    private static String unescapeJava(String body) {
        return body.replace("\\\\", "\\");
    }
}
