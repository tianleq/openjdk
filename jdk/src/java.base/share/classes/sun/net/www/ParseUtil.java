/*
 * Copyright (c) 1998, 2007, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.net.www;

import java.util.BitSet;
import java.io.UnsupportedEncodingException;
import java.io.File;
import java.net.URL;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import sun.nio.cs.ThreadLocalCoders;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * A class that contains useful routines common to sun.net.www
 * @author  Mike McCloskey
 */

public class ParseUtil {
    static BitSet encodedInPath;

    static {
        encodedInPath = new BitSet(256);

        // Set the bits corresponding to characters that are encoded in the
        // path component of a URI.

        // These characters are reserved in the path segment as described in
        // RFC2396 section 3.3.
        encodedInPath.set('=');
        encodedInPath.set(';');
        encodedInPath.set('?');
        encodedInPath.set('/');

        // These characters are defined as excluded in RFC2396 section 2.4.3
        // and must be escaped if they occur in the data part of a URI.
        encodedInPath.set('#');
        encodedInPath.set(' ');
        encodedInPath.set('<');
        encodedInPath.set('>');
        encodedInPath.set('%');
        encodedInPath.set('"');
        encodedInPath.set('{');
        encodedInPath.set('}');
        encodedInPath.set('|');
        encodedInPath.set('\\');
        encodedInPath.set('^');
        encodedInPath.set('[');
        encodedInPath.set(']');
        encodedInPath.set('`');

        // US ASCII control characters 00-1F and 7F.
        for (int i=0; i<32; i++)
            encodedInPath.set(i);
        encodedInPath.set(127);
    }

    /**
     * Constructs an encoded version of the specified path string suitable
     * for use in the construction of a URL.
     *
     * A path separator is replaced by a forward slash. The string is UTF8
     * encoded. The % escape sequence is used for characters that are above
     * 0x7F or those defined in RFC2396 as reserved or excluded in the path
     * component of a URL.
     */
    public static String encodePath(String path) {
        return encodePath(path, true);
    }
    /*
     * flag indicates whether path uses platform dependent
     * File.separatorChar or not. True indicates path uses platform
     * dependent File.separatorChar.
     */
    public static String encodePath(String path, boolean flag) {
        char[] retCC = new char[path.length() * 2 + 16];
        int    retLen = 0;
        char[] pathCC = path.toCharArray();

        int n = path.length();
        for (int i=0; i<n; i++) {
            char c = pathCC[i];
            if ((!flag && c == '/') || (flag && c == File.separatorChar))
                retCC[retLen++] = '/';
            else {
                if (c <= 0x007F) {
                    if (c >= 'a' && c <= 'z' ||
                        c >= 'A' && c <= 'Z' ||
                        c >= '0' && c <= '9') {
                        retCC[retLen++] = c;
                    } else
                    if (encodedInPath.get(c))
                        retLen = escape(retCC, c, retLen);
                    else
                        retCC[retLen++] = c;
                } else if (c > 0x07FF) {
                    retLen = escape(retCC, (char)(0xE0 | ((c >> 12) & 0x0F)), retLen);
                    retLen = escape(retCC, (char)(0x80 | ((c >>  6) & 0x3F)), retLen);
                    retLen = escape(retCC, (char)(0x80 | ((c >>  0) & 0x3F)), retLen);
                } else {
                    retLen = escape(retCC, (char)(0xC0 | ((c >>  6) & 0x1F)), retLen);
                    retLen = escape(retCC, (char)(0x80 | ((c >>  0) & 0x3F)), retLen);
                }
            }
            //worst case scenario for character [0x7ff-] every single
            //character will be encoded into 9 characters.
            if (retLen + 9 > retCC.length) {
                int newLen = retCC.length * 2 + 16;
                if (newLen < 0) {
                    newLen = Integer.MAX_VALUE;
                }
                char[] buf = new char[newLen];
                System.arraycopy(retCC, 0, buf, 0, retLen);
                retCC = buf;
            }
        }
        return new String(retCC, 0, retLen);
    }

    /**
     * Appends the URL escape sequence for the specified char to the
     * specified StringBuffer.
     */
    private static int escape(char[] cc, char c, int index) {
        cc[index++] = '%';
        cc[index++] = Character.forDigit((c >> 4) & 0xF, 16);
        cc[index++] = Character.forDigit(c & 0xF, 16);
        return index;
    }

    /**
     * Un-escape and return the character at position i in string s.
     */
    private static byte unescape(String s, int i) {
        return (byte) Integer.parseInt(s, i + 1, i + 3, 16);
    }


    /**
     * Returns a new String constructed from the specified String by replacing
     * the URL escape sequences and UTF8 encoding with the characters they
     * represent.
     */
    public static String decode(String s) {
        int n = s.length();
        if ((n == 0) || (s.indexOf('%') < 0))
            return s;

        StringBuilder sb = new StringBuilder(n);
        ByteBuffer bb = ByteBuffer.allocate(n);
        CharBuffer cb = CharBuffer.allocate(n);
        CharsetDecoder dec = ThreadLocalCoders.decoderFor("UTF-8")
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

        char c = s.charAt(0);
        for (int i = 0; i < n;) {
            assert c == s.charAt(i);
            if (c != '%') {
                sb.append(c);
                if (++i >= n)
                    break;
                c = s.charAt(i);
                continue;
            }
            bb.clear();
            int ui = i;
            for (;;) {
                assert (n - i >= 2);
                try {
                    bb.put(unescape(s, i));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException();
                }
                i += 3;
                if (i >= n)
                    break;
                c = s.charAt(i);
                if (c != '%')
                    break;
            }
            bb.flip();
            cb.clear();
            dec.reset();
            CoderResult cr = dec.decode(bb, cb, true);
            if (cr.isError())
                throw new IllegalArgumentException("Error decoding percent encoded characters");
            cr = dec.flush(cb);
            if (cr.isError())
                throw new IllegalArgumentException("Error decoding percent encoded characters");
            sb.append(cb.flip().toString());
        }

        return sb.toString();
    }

    /**
     * Returns a canonical version of the specified string.
     */
    public String canonizeString(String file) {
        int i = 0;
        int lim = file.length();

        // Remove embedded /../
        while ((i = file.indexOf("/../")) >= 0) {
            if ((lim = file.lastIndexOf('/', i - 1)) >= 0) {
                file = file.substring(0, lim) + file.substring(i + 3);
            } else {
                file = file.substring(i + 3);
            }
        }
        // Remove embedded /./
        while ((i = file.indexOf("/./")) >= 0) {
            file = file.substring(0, i) + file.substring(i + 2);
        }
        // Remove trailing ..
        while (file.endsWith("/..")) {
            i = file.indexOf("/..");
            if ((lim = file.lastIndexOf('/', i - 1)) >= 0) {
                file = file.substring(0, lim+1);
            } else {
                file = file.substring(0, i);
            }
        }
        // Remove trailing .
        if (file.endsWith("/."))
            file = file.substring(0, file.length() -1);

        return file;
    }

    public static URL fileToEncodedURL(File file)
        throws MalformedURLException
    {
        String path = file.getAbsolutePath();
        path = ParseUtil.encodePath(path);
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (!path.endsWith("/") && file.isDirectory()) {
            path = path + "/";
        }
        return new URL("file", "", path);
    }

    public static java.net.URI toURI(URL url) {
        String protocol = url.getProtocol();
        String auth = url.getAuthority();
        String path = url.getPath();
        String query = url.getQuery();
        String ref = url.getRef();
        if (path != null && !(path.startsWith("/")))
            path = "/" + path;

        //
        // In java.net.URI class, a port number of -1 implies the default
        // port number. So get it stripped off before creating URI instance.
        //
        if (auth != null && auth.endsWith(":-1"))
            auth = auth.substring(0, auth.length() - 3);

        java.net.URI uri;
        try {
            uri = createURI(protocol, auth, path, query, ref);
        } catch (java.net.URISyntaxException e) {
            uri = null;
        }
        return uri;
    }

    //
    // createURI() and its auxiliary code are cloned from java.net.URI.
    // Most of the code are just copy and paste, except that quote()
    // has been modified to avoid double-escape.
    //
    // Usually it is unacceptable, but we're forced to do it because
    // otherwise we need to change public API, namely java.net.URI's
    // multi-argument constructors. It turns out that the changes cause
    // incompatibilities so can't be done.
    //
    private static URI createURI(String scheme,
                                 String authority,
                                 String path,
                                 String query,
                                 String fragment) throws URISyntaxException
    {
        String s = toString(scheme, null,
                            authority, null, null, -1,
                            path, query, fragment);
        checkPath(s, scheme, path);
        return new URI(s);
    }

    private static String toString(String scheme,
                            String opaquePart,
                            String authority,
                            String userInfo,
                            String host,
                            int port,
                            String path,
                            String query,
                            String fragment)
    {
        StringBuffer sb = new StringBuffer();
        if (scheme != null) {
            sb.append(scheme);
            sb.append(':');
        }
        appendSchemeSpecificPart(sb, opaquePart,
                                 authority, userInfo, host, port,
                                 path, query);
        appendFragment(sb, fragment);
        return sb.toString();
    }

    private static void appendSchemeSpecificPart(StringBuffer sb,
                                          String opaquePart,
                                          String authority,
                                          String userInfo,
                                          String host,
                                          int port,
                                          String path,
                                          String query)
    {
        if (opaquePart != null) {
            /* check if SSP begins with an IPv6 address
             * because we must not quote a literal IPv6 address
             */
            if (opaquePart.startsWith("//[")) {
                int end =  opaquePart.indexOf(']');
                if (end != -1 && opaquePart.indexOf(':')!=-1) {
                    String doquote, dontquote;
                    if (end == opaquePart.length()) {
                        dontquote = opaquePart;
                        doquote = "";
                    } else {
                        dontquote = opaquePart.substring(0,end+1);
                        doquote = opaquePart.substring(end+1);
                    }
                    sb.append (dontquote);
                    sb.append(quote(doquote, L_URIC, H_URIC));
                }
            } else {
                sb.append(quote(opaquePart, L_URIC, H_URIC));
            }
        } else {
            appendAuthority(sb, authority, userInfo, host, port);
            if (path != null)
                sb.append(quote(path, L_PATH, H_PATH));
            if (query != null) {
                sb.append('?');
                sb.append(quote(query, L_URIC, H_URIC));
            }
        }
    }

    private static void appendAuthority(StringBuffer sb,
                                 String authority,
                                 String userInfo,
                                 String host,
                                 int port)
    {
        if (host != null) {
            sb.append("//");
            if (userInfo != null) {
                sb.append(quote(userInfo, L_USERINFO, H_USERINFO));
                sb.append('@');
            }
            boolean needBrackets = ((host.indexOf(':') >= 0)
                                    && !host.startsWith("[")
                                    && !host.endsWith("]"));
            if (needBrackets) sb.append('[');
            sb.append(host);
            if (needBrackets) sb.append(']');
            if (port != -1) {
                sb.append(':');
                sb.append(port);
            }
        } else if (authority != null) {
            sb.append("//");
            if (authority.startsWith("[")) {
                int end = authority.indexOf(']');
                if (end != -1 && authority.indexOf(':')!=-1) {
                    String doquote, dontquote;
                    if (end == authority.length()) {
                        dontquote = authority;
                        doquote = "";
                    } else {
                        dontquote = authority.substring(0,end+1);
                        doquote = authority.substring(end+1);
                    }
                    sb.append (dontquote);
                    sb.append(quote(doquote,
                            L_REG_NAME | L_SERVER,
                            H_REG_NAME | H_SERVER));
                }
            } else {
                sb.append(quote(authority,
                            L_REG_NAME | L_SERVER,
                            H_REG_NAME | H_SERVER));
            }
        }
    }

    private static void appendFragment(StringBuffer sb, String fragment) {
        if (fragment != null) {
            sb.append('#');
            sb.append(quote(fragment, L_URIC, H_URIC));
        }
    }

    // Quote any characters in s that are not permitted
    // by the given mask pair
    //
    private static String quote(String s, long lowMask, long highMask) {
        int n = s.length();
        StringBuffer sb = null;
        boolean allowNonASCII = ((lowMask & L_ESCAPED) != 0);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '\u0080') {
                if (!match(c, lowMask, highMask) && !isEscaped(s, i)) {
                    if (sb == null) {
                        sb = new StringBuffer();
                        sb.append(s, 0, i);
                    }
                    appendEscape(sb, (byte)c);
                } else {
                    if (sb != null)
                        sb.append(c);
                }
            } else if (allowNonASCII
                       && (Character.isSpaceChar(c)
                           || Character.isISOControl(c))) {
                if (sb == null) {
                    sb = new StringBuffer();
                    sb.append(s, 0, i);
                }
                appendEncoded(sb, c);
            } else {
                if (sb != null)
                    sb.append(c);
            }
        }
        return (sb == null) ? s : sb.toString();
    }

    //
    // To check if the given string has an escaped triplet
    // at the given position
    //
    private static boolean isEscaped(String s, int pos) {
        if (s == null || (s.length() <= (pos + 2)))
            return false;

        return s.charAt(pos) == '%'
               && match(s.charAt(pos + 1), L_HEX, H_HEX)
               && match(s.charAt(pos + 2), L_HEX, H_HEX);
    }

    private static void appendEncoded(StringBuffer sb, char c) {
        ByteBuffer bb = null;
        try {
            bb = ThreadLocalCoders.encoderFor("UTF-8")
                .encode(CharBuffer.wrap("" + c));
        } catch (CharacterCodingException x) {
            assert false;
        }
        while (bb.hasRemaining()) {
            int b = bb.get() & 0xff;
            if (b >= 0x80)
                appendEscape(sb, (byte)b);
            else
                sb.append((char)b);
        }
    }

    private final static char[] hexDigits = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    private static void appendEscape(StringBuffer sb, byte b) {
        sb.append('%');
        sb.append(hexDigits[(b >> 4) & 0x0f]);
        sb.append(hexDigits[(b >> 0) & 0x0f]);
    }

    // Tell whether the given character is permitted by the given mask pair
    private static boolean match(char c, long lowMask, long highMask) {
        if (c < 64)
            return ((1L << c) & lowMask) != 0;
        if (c < 128)
            return ((1L << (c - 64)) & highMask) != 0;
        return false;
    }

    // If a scheme is given then the path, if given, must be absolute
    //
    private static void checkPath(String s, String scheme, String path)
        throws URISyntaxException
    {
        if (scheme != null) {
            if ((path != null)
                && ((path.length() > 0) && (path.charAt(0) != '/')))
                throw new URISyntaxException(s,
                                             "Relative path in absolute URI");
        }
    }


    // -- Character classes for parsing --

    // Compute a low-order mask for the characters
    // between first and last, inclusive
    private static long lowMask(char first, char last) {
        long m = 0;
        int f = Math.max(Math.min(first, 63), 0);
        int l = Math.max(Math.min(last, 63), 0);
        for (int i = f; i <= l; i++)
            m |= 1L << i;
        return m;
    }

    // Compute the low-order mask for the characters in the given string
    private static long lowMask(String chars) {
        int n = chars.length();
        long m = 0;
        for (int i = 0; i < n; i++) {
            char c = chars.charAt(i);
            if (c < 64)
                m |= (1L << c);
        }
        return m;
    }

    // Compute a high-order mask for the characters
    // between first and last, inclusive
    private static long highMask(char first, char last) {
        long m = 0;
        int f = Math.max(Math.min(first, 127), 64) - 64;
        int l = Math.max(Math.min(last, 127), 64) - 64;
        for (int i = f; i <= l; i++)
            m |= 1L << i;
        return m;
    }

    // Compute the high-order mask for the characters in the given string
    private static long highMask(String chars) {
        int n = chars.length();
        long m = 0;
        for (int i = 0; i < n; i++) {
            char c = chars.charAt(i);
            if ((c >= 64) && (c < 128))
                m |= (1L << (c - 64));
        }
        return m;
    }


    // Character-class masks

    // digit    = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" |
    //            "8" | "9"
    private static final long L_DIGIT = lowMask('0', '9');
    private static final long H_DIGIT = 0L;

    // hex           =  digit | "A" | "B" | "C" | "D" | "E" | "F" |
    //                          "a" | "b" | "c" | "d" | "e" | "f"
    private static final long L_HEX = L_DIGIT;
    private static final long H_HEX = highMask('A', 'F') | highMask('a', 'f');

    // upalpha  = "A" | "B" | "C" | "D" | "E" | "F" | "G" | "H" | "I" |
    //            "J" | "K" | "L" | "M" | "N" | "O" | "P" | "Q" | "R" |
    //            "S" | "T" | "U" | "V" | "W" | "X" | "Y" | "Z"
    private static final long L_UPALPHA = 0L;
    private static final long H_UPALPHA = highMask('A', 'Z');

    // lowalpha = "a" | "b" | "c" | "d" | "e" | "f" | "g" | "h" | "i" |
    //            "j" | "k" | "l" | "m" | "n" | "o" | "p" | "q" | "r" |
    //            "s" | "t" | "u" | "v" | "w" | "x" | "y" | "z"
    private static final long L_LOWALPHA = 0L;
    private static final long H_LOWALPHA = highMask('a', 'z');

    // alpha         = lowalpha | upalpha
    private static final long L_ALPHA = L_LOWALPHA | L_UPALPHA;
    private static final long H_ALPHA = H_LOWALPHA | H_UPALPHA;

    // alphanum      = alpha | digit
    private static final long L_ALPHANUM = L_DIGIT | L_ALPHA;
    private static final long H_ALPHANUM = H_DIGIT | H_ALPHA;

    // mark          = "-" | "_" | "." | "!" | "~" | "*" | "'" |
    //                 "(" | ")"
    private static final long L_MARK = lowMask("-_.!~*'()");
    private static final long H_MARK = highMask("-_.!~*'()");

    // unreserved    = alphanum | mark
    private static final long L_UNRESERVED = L_ALPHANUM | L_MARK;
    private static final long H_UNRESERVED = H_ALPHANUM | H_MARK;

    // reserved      = ";" | "/" | "?" | ":" | "@" | "&" | "=" | "+" |
    //                 "$" | "," | "[" | "]"
    // Added per RFC2732: "[", "]"
    private static final long L_RESERVED = lowMask(";/?:@&=+$,[]");
    private static final long H_RESERVED = highMask(";/?:@&=+$,[]");

    // The zero'th bit is used to indicate that escape pairs and non-US-ASCII
    // characters are allowed; this is handled by the scanEscape method below.
    private static final long L_ESCAPED = 1L;
    private static final long H_ESCAPED = 0L;

    // Dash, for use in domainlabel and toplabel
    private static final long L_DASH = lowMask("-");
    private static final long H_DASH = highMask("-");

    // uric          = reserved | unreserved | escaped
    private static final long L_URIC = L_RESERVED | L_UNRESERVED | L_ESCAPED;
    private static final long H_URIC = H_RESERVED | H_UNRESERVED | H_ESCAPED;

    // pchar         = unreserved | escaped |
    //                 ":" | "@" | "&" | "=" | "+" | "$" | ","
    private static final long L_PCHAR
        = L_UNRESERVED | L_ESCAPED | lowMask(":@&=+$,");
    private static final long H_PCHAR
        = H_UNRESERVED | H_ESCAPED | highMask(":@&=+$,");

    // All valid path characters
    private static final long L_PATH = L_PCHAR | lowMask(";/");
    private static final long H_PATH = H_PCHAR | highMask(";/");

    // userinfo      = *( unreserved | escaped |
    //                    ";" | ":" | "&" | "=" | "+" | "$" | "," )
    private static final long L_USERINFO
        = L_UNRESERVED | L_ESCAPED | lowMask(";:&=+$,");
    private static final long H_USERINFO
        = H_UNRESERVED | H_ESCAPED | highMask(";:&=+$,");

    // reg_name      = 1*( unreserved | escaped | "$" | "," |
    //                     ";" | ":" | "@" | "&" | "=" | "+" )
    private static final long L_REG_NAME
        = L_UNRESERVED | L_ESCAPED | lowMask("$,;:@&=+");
    private static final long H_REG_NAME
        = H_UNRESERVED | H_ESCAPED | highMask("$,;:@&=+");

    // All valid characters for server-based authorities
    private static final long L_SERVER
        = L_USERINFO | L_ALPHANUM | L_DASH | lowMask(".:@[]");
    private static final long H_SERVER
        = H_USERINFO | H_ALPHANUM | H_DASH | highMask(".:@[]");
}
