/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.ldap;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.*;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.novell.ldap.LDAPAttribute;
import com.novell.ldap.LDAPAttributeSet;
import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPDN;
import com.novell.ldap.LDAPEntry;
import com.novell.ldap.LDAPException;
import com.novell.ldap.LDAPJSSESecureSocketFactory;
import com.novell.ldap.LDAPSearchConstraints;
import com.novell.ldap.LDAPSearchResults;
import com.novell.ldap.LDAPSocketFactory;
import com.xpn.xwiki.XWikiContext;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/**
 * LDAP communication tool.
 *
 * @version $Id$
 * @since 8.3
 */
public class XWikiLDAPConnection {
    /**
     * Logging tool.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(XWikiLDAPConnection.class);

    /**
     * The LDAP connection.
     */
    private LDAPConnection connection;

    /**
     * LDAP attributes that should be treated as binary data.
     */
    private Set<String> binaryAttributes = new HashSet<>();

    private final XWikiLDAPConfig configuration;

    /**
     * @deprecated since 8.5, use {@link #XWikiLDAPConnection(XWikiLDAPConfig)} instead
     */
    @Deprecated
    public XWikiLDAPConnection() {
        this(new XWikiLDAPConfig(null));
    }

    /**
     * @param configuration the configuration to use
     * @since 9.0
     */
    public XWikiLDAPConnection(XWikiLDAPConfig configuration) {
        this.configuration = configuration;
    }

    /**
     * @param connection the connection to copy
     */
    public XWikiLDAPConnection(org.xwiki.contrib.ldap.XWikiLDAPConnection connection) {
        this();

        this.connection = connection.connection;
        this.binaryAttributes = connection.binaryAttributes;
    }

    /**
     * @param context the XWiki context.
     * @return the maximum number of milliseconds the client waits for any operation under these constraints to
     * complete.
     */
    private int getTimeout(XWikiContext context) {
        return this.configuration.getLDAPTimeout();
    }

    /**
     * @param context the XWiki context.
     * @return the maximum number of search results to be returned from a search operation.
     */
    private int getMaxResults(XWikiContext context) {
        return this.configuration.getLDAPMaxResults();
    }

    /**
     * @return the {@link LDAPConnection}.
     */
    public LDAPConnection getConnection() {
        return this.connection;
    }

    /**
     * Open a LDAP connection.
     *
     * @param ldapUserName the user name to connect to LDAP server.
     * @param password     the password to connect to LDAP server.
     * @param context      the XWiki context.
     * @return true if connection succeed, false otherwise.
     * @throws XWikiLDAPException error when trying to open connection.
     */
    public boolean open(String ldapUserName, String password, XWikiContext context) throws XWikiLDAPException {
        // open LDAP
        int ldapPort = this.configuration.getLDAPPort();
        String ldapHost = this.configuration.getLDAPParam("ldap_server", "localhost");

        // allow to use the given user and password also as the LDAP bind user and password
        String bindDN = this.configuration.getLDAPBindDN(ldapUserName, password);
        String bindPassword = this.configuration.getLDAPBindPassword(ldapUserName, password);

        boolean bind;
        if ("1".equals(this.configuration.getLDAPParam("ldap_ssl", "0"))) {
            String keyStore = this.configuration.getLDAPParam("ldap_ssl.keystore", "");

            LOGGER.debug("Connecting to LDAP using SSL");

            bind = open(ldapHost, ldapPort, bindDN, bindPassword, keyStore, true, context);
        } else {
            LOGGER.debug("AXWIKI:binds " + bindDN);
            bind = open(ldapHost, ldapPort, bindDN, bindPassword, null, false, context);
        }

        return bind;
    }

    /**
     * Open LDAP connection.
     *
     * @param ldapHost   the host of the server to connect to.
     * @param ldapPort   the port of the server to connect to.
     * @param loginDN    the user DN to connect to LDAP server.
     * @param password   the password to connect to LDAP server.
     * @param pathToKeys the path to SSL keystore to use.
     * @param ssl        if true connect using SSL.
     * @param context    the XWiki context.
     * @return true if the connection succeed, false otherwise.
     * @throws XWikiLDAPException error when trying to open connection.
     */
    public boolean open(String ldapHost, int ldapPort, String loginDN, String password, String pathToKeys, boolean ssl,
                        XWikiContext context) throws XWikiLDAPException {
        int port = ldapPort;

        String dn = createLoginDNByUID(loginDN);
        LOGGER.debug("AXWIKI:new dn:" + dn );
        if (port <= 0) {
            port = ssl ? LDAPConnection.DEFAULT_SSL_PORT : LDAPConnection.DEFAULT_PORT;
        }

        setBinaryAttributes(this.configuration.getBinaryAttributes());

        try {
            if (ssl) {
                // Dynamically set JSSE as a security provider
                Security.addProvider(this.configuration.getSecureProvider());

                if (pathToKeys != null && pathToKeys.length() > 0) {
                    // Dynamically set the property that JSSE uses to identify
                    // the keystore that holds trusted root certificates

                    System.setProperty("javax.net.ssl.trustStore", pathToKeys);
                    // obviously unnecessary: sun default pwd = "changeit"
                    // System.setProperty("javax.net.ssl.trustStorePassword", sslpwd);
                }

                LDAPSocketFactory ssf = new LDAPJSSESecureSocketFactory();

                // Set the socket factory as the default for all future connections
                // LDAPConnection.setSocketFactory(ssf);

                // Note: the socket factory can also be passed in as a parameter
                // to the constructor to set it for this connection only.
                this.connection = new LDAPConnection(ssf);
            } else {
                this.connection = new LDAPConnection();
            }

            // connect
            connect(ldapHost, port);

            // set referral following
            LDAPSearchConstraints constraints = new LDAPSearchConstraints(this.connection.getConstraints());
            constraints.setTimeLimit(getTimeout(context));
            constraints.setMaxResults(getMaxResults(context));
            constraints.setReferralFollowing(true);
            constraints.setReferralHandler(new LDAPPluginReferralHandler(loginDN, password, context));
            this.connection.setConstraints(constraints);

            // bind
            bind(dn, password);
        } catch (UnsupportedEncodingException e) {
            throw new XWikiLDAPException("LDAP bind failed with UnsupportedEncodingException.", e);
        } catch (LDAPException e) {
            throw new XWikiLDAPException("LDAP bind failed with LDAPException.", e);
        }

        return true;
    }

    public String createLoginDNByUID(String loginDN) {
        String dn = getDnFromLdap(loginDN);
        return dn;
    }

    private String getDnFromLdap(String origLoginDn) {
        // call ldap anonymously
        if (origLoginDn.contains("uid")){
            LOGGER.debug("AXWIKI:origLoginDN:{}",origLoginDn);
            String withoutEscape = origLoginDn.replaceAll("\\\\", "");
            LOGGER.debug("AXWIKI:withoutescapeLoginDN:{}",withoutEscape);
            return withoutEscape;

        }

        String ldapHost = this.configuration.getLDAPParam( "ldap_server", "localhost");
        int ldapPort = this.configuration.getLDAPPort();
        String baseDn = this.configuration.getLDAPParam( "ldap_base_DN","o=ibm.com");
        LDAPConnection lc = new LDAPConnection();
        try {
            lc.connect(ldapHost, ldapPort);
            lc.bind(LDAPConnection.LDAP_V3, "", "".getBytes(StandardCharsets.UTF_8));
            String[] attrs = {"uid"};
            String filter = getFilter(origLoginDn);
            LOGGER.debug("AXWIKI:new filter for anon:" + filter);
            LDAPSearchResults searchResults = lc.search(baseDn,
                    LDAPConnection.SCOPE_SUB, filter, attrs,false);

            while (searchResults.hasMore()) {

                LDAPEntry nextEntry = null;

                try {

                    nextEntry = searchResults.next();

                } catch (LDAPException e) {

                    System.out.println("Error: " + e.toString());

                    if (e.getResultCode() == LDAPException.LDAP_TIMEOUT || e.getResultCode() == LDAPException.CONNECT_ERROR)
                        break;
                    else
                        continue;
                }


                String dn = nextEntry.getDN();
                return dn;
            }
            lc.disconnect();
        } catch (LDAPException | InvalidNameException e) {
            e.printStackTrace();
        }
        return origLoginDn;
    }

    private String getFilter(String userMail) throws InvalidNameException {
        String filter = String.format("(mail=%s)", userMail);
        return filter;
    }

    /**
     * Connect to server.
     *
     * @param ldapHost the host of the server to connect to.
     * @param port     the port of the server to connect to.
     * @throws LDAPException error when trying to connect.
     */
    private void connect(String ldapHost, int port) throws LDAPException {
        LOGGER.debug("Connection to LDAP server [{}:{}]", ldapHost, port);

        // connect to the server
        this.connection.connect(ldapHost, port);
    }

    /**
     * Bind to LDAP server.
     *
     * @param loginDN  the user DN to connect to LDAP server.
     * @param password the password to connect to LDAP server.
     * @throws UnsupportedEncodingException error when converting provided password to UTF-8 table.
     * @throws LDAPException                error when trying to bind.
     */
    public void bind(String loginDN, String password) throws UnsupportedEncodingException, LDAPException {
        loginDN = loginDN.replaceAll("\\\\", "");
        LOGGER.debug("Binding to LDAP server with credentials: login=[{}]", loginDN);

        // authenticate to the server
        this.connection.bind(LDAPConnection.LDAP_V3, loginDN, password.getBytes("UTF8"));
    }

    /**
     * Close LDAP connection.
     */
    public void close() {
        try {
            if (this.connection != null) {
                this.connection.disconnect();
            }
        } catch (LDAPException e) {
            LOGGER.debug("LDAP close failed.", e);
        }
    }

    /**
     * Check if provided password is correct provided users's password.
     *
     * @param userDN   the user.
     * @param password the password.
     * @return true if the password is valid, false otherwise.
     */
    public boolean checkPassword(String userDN, String password) {
        return checkPassword(userDN, password, "userPassword");
    }

    /**
     * Check if provided password is correct provided users's password.
     *
     * @param userDN        the user.
     * @param password      the password.
     * @param passwordField the name of the LDAP field containing the password.
     * @return true if the password is valid, false otherwise.
     */
    public boolean checkPassword(String userDN, String password, String passwordField) {
        try {
            LDAPAttribute attribute = new LDAPAttribute(passwordField, password);
            return this.connection.compare(userDN, attribute);
        } catch (LDAPException e) {
            if (e.getResultCode() == LDAPException.NO_SUCH_OBJECT) {
                LOGGER.debug("Unable to locate user_dn [{}]", userDN, e);
            } else if (e.getResultCode() == LDAPException.NO_SUCH_ATTRIBUTE) {
                LOGGER.debug("Unable to verify password because userPassword attribute not found.", e);
            } else {
                LOGGER.debug("Unable to verify password", e);
            }
        }

        return false;
    }

    /**
     * Execute a LDAP search query and return the first entry.
     *
     * @param baseDN    the root DN from where to search.
     * @param filter    the LDAP filter.
     * @param attr      the attributes names of values to return.
     * @param ldapScope the scope of the entries to search. The following are the valid options:
     *                  <ul>
     *                  <li>SCOPE_BASE - searches only the base DN
     *                  <li>SCOPE_ONE - searches only entries under the base DN
     *                  <li>SCOPE_SUB - searches the base DN and all entries within its subtree
     *                  </ul>
     * @return the found LDAP attributes.
     */
    public List<XWikiLDAPSearchAttribute> searchLDAP(String baseDN, String filter, String[] attr, int ldapScope) {
        List<XWikiLDAPSearchAttribute> searchAttributeList = null;
        // baseDn is original. need to  replace uid
        LOGGER.debug("AXWIKI: input for search:baseDN:"+ baseDN);
        String newBaseDN = createLoginDNByUID(baseDN);
        LOGGER.debug("AXWIKI: NEW input for search:baseDN:"+ newBaseDN);
        // filter return all attributes return attrs and values time out value
        try (PagedLDAPSearchResults searchResults = searchPaginated(newBaseDN, ldapScope, filter, attr, false)) {
            if (!searchResults.hasMore()) {
                return null;
            }

            LDAPEntry nextEntry = searchResults.next();
            String foundDN = nextEntry.getDN();

            searchAttributeList = new ArrayList<>();

            searchAttributeList.add(new XWikiLDAPSearchAttribute("dn", foundDN));

            LDAPAttributeSet attributeSet = nextEntry.getAttributeSet();

            ldapToXWikiAttribute(searchAttributeList, attributeSet);
        } catch (LDAPException e) {
            LOGGER.debug("LDAP Search failed", e);
        }

        LOGGER.debug("LDAP search found attributes [{}]", searchAttributeList);

        return searchAttributeList;
    }

    /**
     * @param baseDN    the root DN from where to search.
     * @param filter    filter the LDAP filter
     * @param attr      the attributes names of values to return
     * @param ldapScope the scope of the entries to search. The following are the valid options:
     *                  <ul>
     *                  <li>SCOPE_BASE - searches only the base DN
     *                  <li>SCOPE_ONE - searches only entries under the base DN
     *                  <li>SCOPE_SUB - searches the base DN and all entries within its subtree
     *                  </ul>
     * @return a result stream. LDAPConnection#abandon should be called when it's not needed anymore.
     * @throws LDAPException error when searching
     * @since 3.3M1
     */
    public LDAPSearchResults search(String baseDN, String filter, String[] attr, int ldapScope) throws LDAPException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("LDAP search: baseDN=[{}] query=[{}] attr=[{}] ldapScope=[{}]", baseDN, filter,
                    attr != null ? Arrays.asList(attr) : null, ldapScope);
        }

        return this.connection.search(baseDN, ldapScope, filter, attr, false);
    }

    /**
     * @param base      the root DN from where to search.
     * @param scope     the scope of the entries to search. The following are the valid options:
     *                  <ul>
     *                  <li>SCOPE_BASE - searches only the base DN
     *                  <li>SCOPE_ONE - searches only entries under the base DN
     *                  <li>SCOPE_SUB - searches the base DN and all entries within its subtree
     *                  </ul>
     * @param filter    filter the LDAP filter
     * @param attrs     the attributes names of values to return
     * @param typesOnly if true, returns the names but not the values of the attributes found. If false, returns the
     *                  names and values for attributes found.
     * @return a result stream. LDAPConnection#abandon should be called when it's not needed anymore.
     * @throws LDAPException error when searching
     * @since 9.3
     */
    public PagedLDAPSearchResults searchPaginated(String base, int scope, String filter, String[] attrs,
                                                  boolean typesOnly) throws LDAPException {
        int pageSize = this.configuration.getSearchPageSize();

        return new PagedLDAPSearchResults(this, base, scope, filter, attrs, typesOnly, pageSize);
    }

    /**
     * Fill provided <code>searchAttributeList</code> with provided LDAP attributes.
     *
     * @param searchAttributeList the XWiki attributes.
     * @param attributeSet        the LDAP attributes.
     */
    public void ldapToXWikiAttribute(List<XWikiLDAPSearchAttribute> searchAttributeList,
                                     LDAPAttributeSet attributeSet) {
        for (LDAPAttribute attribute : (Set<LDAPAttribute>) attributeSet) {
            String attributeName = attribute.getName();

            if (!isBinaryAttribute(attributeName)) {
                LOGGER.debug("  - values for attribute [{}]", attributeName);

                Enumeration<String> allValues = attribute.getStringValues();

                if (allValues != null) {
                    while (allValues.hasMoreElements()) {
                        String value = allValues.nextElement();

                        LOGGER.debug("    |- [{}]", value);

                        searchAttributeList.add(new XWikiLDAPSearchAttribute(attributeName, value));
                    }
                }
            } else {
                LOGGER.debug("  - attribute [{}] is binary", attributeName);

                Enumeration<byte[]> allValues = attribute.getByteValues();

                if (allValues != null) {
                    while (allValues.hasMoreElements()) {
                        byte[] value = allValues.nextElement();

                        searchAttributeList.add(new XWikiLDAPSearchAttribute(attributeName, value));
                    }
                }
            }
        }
    }

    /**
     * Fully escape DN value (the part after the =).
     * <p>
     * For example, for the dn value "Acme, Inc", the escapeLDAPDNValue method returns "Acme\, Inc".
     * </p>
     *
     * @param value the DN value to escape
     * @return the escaped version o the DN value
     */
    public static String escapeLDAPDNValue(String value) {
        return StringUtils.isBlank(value) ? value : LDAPDN.escapeRDN("key=" + value).substring(4);
    }

    /**
     * Escape part of a LDAP query filter.
     *
     * @param value the value to escape
     * @return the escaped version
     */
    public static String escapeLDAPSearchFilter(String value) {
        if (value == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char curChar = value.charAt(i);
            switch (curChar) {
                case '\\':
                    sb.append("\\5c");
                    break;
                case '*':
                    sb.append("\\2a");
                    break;
                case '(':
                    sb.append("\\28");
                    break;
                case ')':
                    sb.append("\\29");
                    break;
                case '\u0000':
                    sb.append("\\00");
                    break;
                default:
                    sb.append(curChar);
            }
        }
        return sb.toString();
    }

    /**
     * Update list of LDAP attributes that should be treated as binary data.
     *
     * @param binaryAttributes set of binary attributes
     */
    private void setBinaryAttributes(Set<String> binaryAttributes) {
        this.binaryAttributes = binaryAttributes;
    }

    /**
     * Checks whether attribute should be treated as binary data.
     *
     * @param attributeName name of attribute to check
     * @return true if attribute should be treated as binary data.
     */
    private boolean isBinaryAttribute(String attributeName) {
        return binaryAttributes.contains(attributeName);
    }
}
