package io.freedriver.sms.invite;

/**
 * The login link in an invitation text. The number is the URL fragment on
 * {@code freedriver.public-base-url}.
 */
public final class InvitationLink {

    private InvitationLink() {
    }

    public static String text(String publicBase, String phone) {
        String base = publicBase == null ? "" : publicBase.strip();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.startsWith("https://") || base.contains("?") || base.contains("#") || base.contains(" ")
                || base.indexOf("://") != base.lastIndexOf("://")) {
            throw new IllegalArgumentException("freedriver.public-base-url");
        }
        return "Sign in to Freedriver: " + base + "/login#" + phone;
    }
}
