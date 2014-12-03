package org.apache.jackrabbit.jcr2spi.security.authorization.jackrabbit.acl;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.security.JackrabbitAccessControlEntry;
import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.spi.QValue;
import org.apache.jackrabbit.spi.QValueFactory;
import org.apache.jackrabbit.spi.commons.conversion.NamePathResolver;
import org.apache.jackrabbit.spi.commons.value.QValueValue;
import org.apache.jackrabbit.spi.commons.value.ValueFactoryQImpl;
import org.apache.jackrabbit.value.ValueHelper;

class AccessControlEntryImpl implements JackrabbitAccessControlEntry {

    /*
     * The principal this entry has been created for.
     */
    private final Principal principal;
    
    /*
     * The privileges in this entry.
     */
    private final Privilege[] privileges;
        
    /*
     * Whether this entry is allowed/denied
     */
    private final boolean isAllow;
    
    /*
     * Restrictions that may apply with this entry.
     */
    private final Map<Name, QValue> restrictions;

    private final NamePathResolver resolver;

    private final QValueFactory qvf;

    /**
     * 
     * @param principal
     * @param privileges
     * @param isAllow
     * @param restrictions
     * @throws RepositoryException 
     */
    AccessControlEntryImpl(Principal principal, Privilege[] privileges, boolean isAllow,
                           Map<String, QValue> restrictions, NamePathResolver resolver,
                           QValueFactory qvf)
                                    throws RepositoryException {
        if (principal == null || (privileges != null && privileges.length == 0)) {
            throw new AccessControlException("An Entry must not have a NULL principal or empty privileges");
        }
        checkAbstract(privileges);
        
        this.principal = principal;
        this.privileges = privileges;
        this.isAllow = isAllow;
        this.resolver = resolver;
        this.qvf = qvf;

        if (restrictions == null || (restrictions.size() == 0)) {
            this.restrictions = Collections.<Name, QValue>emptyMap();
        } else {
            this.restrictions = new HashMap<Name, QValue>(restrictions.size());
            for (String restName : restrictions.keySet()) {
                this.restrictions.put(resolver.getQName(restName), restrictions.get(restName));
            }
        }
    }
    
    @Override
    public Principal getPrincipal() {
        return principal;
    }

    @Override
    public Privilege[] getPrivileges() {
        return privileges;
    }
    
    @Override
    public boolean isAllow() {
        return isAllow;
    }
    
    @Override
    public String[] getRestrictionNames() throws RepositoryException {
        List<String> restNames = new ArrayList<String>(restrictions.size());
        for (Name restName : restrictions.keySet()) {
            restNames.add(resolver.getJCRName(restName));
        }
        return restNames.toArray(new String[restNames.size()]);
    }

    @Override
    public Value getRestriction(String restrictionName)
            throws ValueFormatException, RepositoryException {
        try {
            Name restName = resolver.getQName(restrictionName);
            if (!restrictions.containsKey(restName)) {
                return null;
            }
            return createJcrValue(restrictions.get(restName));
        } catch (IllegalStateException e) {
            throw new RepositoryException(e.getMessage());
        }
    }

    /*
     * As of Jackrabbit 2.8, this extention has been added to the Jackrabbit API.
     * However, Jackrabbit (before) OAK doesn't support mv. restrictions. Thus simply
     * return an array containing the single restriction value.
     */
    @Override
    public Value[] getRestrictions(String restrictionName)
            throws RepositoryException {
        return new Value[] {getRestriction(restrictionName)};
    }
    
    //-------------------------------------------------------------< private >---
    private void checkAbstract(Privilege[] privileges) throws AccessControlException {
        for (Privilege privilege : privileges) {
            if (privilege.isAbstract()) {
                throw new AccessControlException("An Entry cannot contain abstract privileges.");
            }
        }
    }

    /**
     * Creates a jcr Value from the given qvalue using the specified
     * factory.
     * @return         the jcr value representing the qvalue.
     */
    private Value createJcrValue(QValue qValue) throws RepositoryException {
        
        // build ValueFactory
        ValueFactoryQImpl valueFactory = new ValueFactoryQImpl(qvf, resolver);

        // build jcr value
        QValueValue jcrValue = new QValueValue(qValue, resolver);
        
        return ValueHelper.copy(jcrValue, valueFactory);
    }
}