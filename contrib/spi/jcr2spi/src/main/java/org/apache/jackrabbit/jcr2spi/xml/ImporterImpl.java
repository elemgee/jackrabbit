/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.jcr2spi.xml;

import org.apache.jackrabbit.jcr2spi.state.NodeState;
import org.apache.jackrabbit.jcr2spi.state.PropertyState;
import org.apache.jackrabbit.jcr2spi.state.ItemStateValidator;
import org.apache.jackrabbit.jcr2spi.state.ItemState;
import org.apache.jackrabbit.jcr2spi.state.SessionItemStateManager;
import org.apache.jackrabbit.jcr2spi.SessionImpl;
import org.apache.jackrabbit.jcr2spi.HierarchyManager;
import org.apache.jackrabbit.jcr2spi.SessionListener;
import org.apache.jackrabbit.jcr2spi.util.ReferenceChangeTracker;
import org.apache.jackrabbit.jcr2spi.nodetype.EffectiveNodeType;
import org.apache.jackrabbit.jcr2spi.operation.AddNode;
import org.apache.jackrabbit.jcr2spi.operation.Remove;
import org.apache.jackrabbit.jcr2spi.operation.AddProperty;
import org.apache.jackrabbit.jcr2spi.operation.SetPropertyValue;
import org.apache.jackrabbit.jcr2spi.operation.SetMixin;
import org.apache.jackrabbit.jcr2spi.operation.Operation;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.util.Base64;
import org.apache.jackrabbit.util.TransientFileFactory;
import org.apache.jackrabbit.spi.IdFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.ImportUUIDBehavior;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.PropertyType;
import javax.jcr.PathNotFoundException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.lock.LockException;
import javax.jcr.version.VersionException;
import javax.jcr.nodetype.ConstraintViolationException;
import org.apache.jackrabbit.spi.NodeId;
import org.apache.jackrabbit.spi.PropertyId;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.spi.QPropertyDefinition;
import org.apache.jackrabbit.spi.QNodeDefinition;
import org.apache.jackrabbit.value.QValue;
import org.apache.jackrabbit.value.ValueHelper;
import org.apache.jackrabbit.value.ValueFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.Reader;
import java.io.IOException;
import java.util.Stack;
import java.util.List;
import java.util.Iterator;

/**
 * <code>ImporterImpl</code>...
 */
public class ImporterImpl implements Importer, SessionListener {

    private static Logger log = LoggerFactory.getLogger(ImporterImpl.class);

    private final NodeState importTarget;
    private final SessionImpl session;
    private final HierarchyManager hierMgr;

    private final SessionItemStateManager stateMgr;
    private final ItemStateValidator validator;
    final IdFactory idFactory;

    private final Stack parents;

    private final int uuidBehavior;
    private final boolean isWspImport;

    private boolean importerClosed;
    private boolean sessionClosed;

    /**
     * helper object that keeps track of remapped uuid's and imported reference
     * properties that might need correcting depending on the uuid mappings
     */
    private final ReferenceChangeTracker refTracker;


    /**
     * Creates a new <code>WorkspaceImporter</code> instance.
     *
     * @param parentPath qualified path of target node where to add the imported
     * subtree
     * @param session
     * @param validator
     * @param uuidBehavior Flag that governs how incoming UUIDs are handled.
     * @throws PathNotFoundException If no node exists at <code>parentPath</code>
     * or if the current session is not granted read access.
     * @throws ConstraintViolationException If the node at <code>parentPath</code>
     * is protected.
     * @throws VersionException If the node at <code>parentPath</code> is not
     * checked-out.
     * @throws LockException If a lock prevents the addition of the subtree.
     * @throws RepositoryException If another error occurs.
     */
    public ImporterImpl(Path parentPath, SessionImpl session, HierarchyManager hierManager,
                     SessionItemStateManager stateManager, ItemStateValidator validator,
                     IdFactory idFactory, int uuidBehavior, boolean isWspImport)
        throws PathNotFoundException, ConstraintViolationException,
        VersionException, LockException, RepositoryException {

        this.validator = validator;
        this.session = session;
        this.hierMgr = hierManager;
        this.stateMgr = stateManager;
        this.idFactory = idFactory;

        // perform preliminary checks
        importTarget = validator.getNodeState(parentPath);
        // DIFF JR: remove check for overall writability on target-node.

        this.uuidBehavior = uuidBehavior;
        this.isWspImport = isWspImport;

        refTracker = new ReferenceChangeTracker();
        parents = new Stack();
        parents.push(importTarget);
    }

    //-----------------------------------------------------------< Importer >---
    /**
     * {@inheritDoc}
     */
    public void start() throws RepositoryException {
        // explicitely set status of importer and start listening on session
        setClosed(false);
    }

   /**
     * {@inheritDoc}
     */
    public void startNode(NodeInfo nodeInfo, List propInfos, NamespaceResolver nsContext)
            throws RepositoryException {
       if (isClosed()) {
           // workspace-importer only: ignore if import has been aborted before.
           return;
       }
       boolean succeeded = false;
       try {
           checkSession();
           NodeState parent = (NodeState) parents.peek();
           if (parent == null) {
               // parent node was skipped, skip this child node also
               parents.push(null); // push null onto stack for skipped node
               log.debug("Skipping node '" + nodeInfo.getName() + "'.");
               return;
           }

           NodeState nodeState = null;
           if (parent.hasChildNodeEntry(nodeInfo.getName())) {
               // a node with that name already exists...
               NodeState.ChildNodeEntry entry = parent.getChildNodeEntry(nodeInfo.getName(), 1);
               NodeState existing = validator.getNodeState(entry.getId());
               QNodeDefinition def = existing.getDefinition();
               if (!def.allowsSameNameSiblings()) {
                   // existing doesn't allow same-name siblings, check for conflicts
                   EffectiveNodeType entExisting = validator.getEffectiveNodeType(existing);
                   if (def.isProtected() && entExisting.includesNodeType(nodeInfo.getNodeTypeName())) {
                       // skip protected node
                       parents.push(null); // push null onto stack for skipped node
                       log.debug("skipping protected node " + hierMgr.safeGetJCRPath(existing.getId()));
                       return;
                   }
                   if (def.isAutoCreated() && entExisting.includesNodeType(nodeInfo.getNodeTypeName())) {
                       // this node has already been auto-created, no need to create it
                       nodeState = existing;
                   } else {
                       throw new ItemExistsException(hierMgr.safeGetJCRPath(existing.getId()));
                   }
               }
           }

           if (nodeState == null) {
               // node does not exist -> create new one
               if (nodeInfo.getId() == null) {
                   // no potential uuid conflict, add new node from given info
                   nodeState = importNode(nodeInfo, parent);
               } else {
                   // potential uuid conflict
                   try {
                       NodeState conflicting = validator.getNodeState(nodeInfo.getId());
                       nodeState = resolveUUIDConflict(parent, conflicting, nodeInfo);
                   } catch (ItemNotFoundException infe) {
                       // no conflict: create new with given uuid
                       nodeState = importNode(nodeInfo, parent);
                   }
               }
           }

           // node state may be 'null' if applicable def is protected
           if (nodeState != null) {
               // process properties
               Iterator iter = propInfos.iterator();
               while (iter.hasNext()) {
                   PropInfo pi = (PropInfo) iter.next();
                   importProperty(pi, nodeState, nsContext);
               }
           }

           // push current nodeState onto stack of parents
           parents.push(nodeState);
           succeeded = true;
       } finally {
           // workspace-importer only: abort the import by closing the importer
           // in case of failure.
           if (!succeeded && isWspImport) {
               setClosed(true);
           }
       }
   }

    /**
     * {@inheritDoc}
     */
    public void endNode(NodeInfo nodeInfo) throws RepositoryException {
        if(isClosed()) {
            // workspace-importer only: ignore if import has been aborted before.
            return;
        }
        parents.pop();
    }

    /**
     * {@inheritDoc}
     */
    public void end() throws RepositoryException {
        if(isClosed()) {
            // workspace-importer only: ignore if import has been aborted before.
            return;
        }

        try {
            checkSession();
            // adjust references refering to remapped uuids
            stateMgr.adjustReferences(refTracker);
        } finally {
            // close this importer since we are done.
            setClosed(true);
        }
    }
    //----------------------------------------------------< SessionListener >---
    /**
     *
     * @param session
     * @see SessionListener#loggingOut(Session)
     */
    public void loggingOut(Session session) {
        // the session will be be valid any more, thus any further calls on
        // the importer must fail
        sessionClosed = true;
    }

    /**
     *
     * @param session
     * @see SessionListener#loggedOut(Session)
     */
    public void loggedOut(Session session) {
        // ignore
    }

    //--------------------------------------------< Importer/Session Status >---
    private void setClosed(boolean isClosed) {
        importerClosed = isClosed;
        if (isClosed) {
            session.removeListener(this);
        } else {
            session.addListener(this);
        }
    }

    private boolean isClosed() {
        return importerClosed;
    }

    private void checkSession() throws RepositoryException {
        if (sessionClosed) {
            throw new RepositoryException("This session has been closed.");
        }
    }

    //----------------------------------------------------< Private methods >---
    /**
     * @param parent
     * @param conflicting
     * @param nodeInfo
     * @return
     * @throws RepositoryException
     */
    NodeState resolveUUIDConflict(NodeState parent, NodeState conflicting,
                                  NodeInfo nodeInfo) throws RepositoryException {
        NodeState nodeState;
        switch (uuidBehavior) {
            case ImportUUIDBehavior.IMPORT_UUID_CREATE_NEW:
                NodeId original = nodeInfo.getId();
                // reset id on nodeInfo to force creation with new uuid:
                nodeInfo.setId(null);
                nodeState = importNode(nodeInfo, parent);
                if (nodeState != null) {
                    // remember uuid mapping
                    EffectiveNodeType ent = validator.getEffectiveNodeType(nodeState);
                    if (ent.includesNodeType(QName.MIX_REFERENCEABLE)) {
                        refTracker.mappedNodeIds(original, nodeState.getNodeId());
                    }
                }
                break;

            case ImportUUIDBehavior.IMPORT_UUID_COLLISION_THROW:
                String msg = "a node with uuid " + nodeInfo.getId() + " already exists!";
                log.debug(msg);
                throw new ItemExistsException(msg);

            case ImportUUIDBehavior.IMPORT_UUID_COLLISION_REMOVE_EXISTING:
                // make sure conflicting node is not importTarget or an ancestor thereof
                Path p0 = hierMgr.getQPath(importTarget.getId());
                Path p1 = hierMgr.getQPath(conflicting.getId());
                try {
                    if (p1.equals(p0) || p1.isAncestorOf(p0)) {
                        msg = "cannot remove ancestor node";
                        log.debug(msg);
                        throw new ConstraintViolationException(msg);
                    }
                } catch (MalformedPathException e) {
                    // should never get here...
                    msg = "internal error: failed to determine degree of relationship";
                    log.error(msg, e);
                    throw new RepositoryException(msg, e);
                }
                // do remove conflicting (recursive) including validation check
                Operation op = Remove.create(conflicting);
                stateMgr.execute(op);
                // create new with given uuid:
                nodeState = importNode(nodeInfo, parent);
                break;

            case ImportUUIDBehavior.IMPORT_UUID_COLLISION_REPLACE_EXISTING:
                NodeId parentId = conflicting.getParentId();
                if (parentId == null) {
                    msg = "Root node cannot be replaced";
                    log.debug(msg);
                    throw new RepositoryException(msg);
                }
                // 'replace' current parent with parent of conflicting
                try {
                    parent = validator.getNodeState(parentId);
                } catch (ItemNotFoundException infe) {
                    // should never get here...
                    msg = "Internal error: failed to retrieve parent state";
                    log.error(msg, infe);
                    throw new RepositoryException(msg, infe);
                }
                // do remove conflicting (recursive), including validation checks
                op = Remove.create(conflicting);
                stateMgr.execute(op);
                // create new with given uuid at same location as conflicting
                nodeState = importNode(nodeInfo, parent);
                break;

            default:
                msg = "Unknown uuidBehavior: " + uuidBehavior;
                log.debug(msg);
                throw new RepositoryException(msg);
        }
        return nodeState;
    }

    /**
     *
     * @param nodeInfo
     * @param parent
     * @return
     * @throws ConstraintViolationException
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    private NodeState importNode(NodeInfo nodeInfo, NodeState parent) throws ConstraintViolationException, ItemNotFoundException, RepositoryException {
        if (parent.hasPropertyName(nodeInfo.getName())) {
            /**
             * a property with the same name already exists; if this property
             * has been imported as well (e.g. through document view import
             * where an element can have the same name as one of the attributes
             * of its parent element) we have to rename the conflicting property;
             *
             * see http://issues.apache.org/jira/browse/JCR-61
             */
            PropertyState conflicting = validator.getPropertyState(parent.getNodeId(), nodeInfo.getName());
            if (conflicting.getStatus() == ItemState.STATUS_NEW) {
                // assume this property has been imported as well;
                // rename conflicting property
                // @todo use better reversible escaping scheme to create unique name
                QName newName = new QName(nodeInfo.getName().getNamespaceURI(), nodeInfo.getName().getLocalName() + "_");
                if (parent.hasPropertyName(newName)) {
                    newName = new QName(newName.getNamespaceURI(), newName.getLocalName() + "_");
                }
                // since name changes, need to find new applicable definition
                QPropertyDefinition propDef;
                if (conflicting.getValues().length == 1) {
                    // could be single- or multi-valued (n == 1)
                    try {
                        // try single-valued
                        propDef = validator.getApplicablePropertyDefinition(newName, conflicting.getType(), false, parent);
                    } catch (ConstraintViolationException cve) {
                        // try multi-valued
                        propDef = validator.getApplicablePropertyDefinition(newName, conflicting.getType(), true, parent);
                    }
                } else {
                    // can only be multi-valued (n == 0 || n > 1)
                    propDef = validator.getApplicablePropertyDefinition(newName, conflicting.getType(), true, parent);
                }

                PropertyId newPId = idFactory.createPropertyId(parent.getNodeId(), newName);
                Operation ap = AddProperty.create(newPId, conflicting.getType(), propDef, conflicting.getValues());
                stateMgr.execute(ap);
                Operation rm = Remove.create(conflicting);
                stateMgr.execute(rm);
            }
        }

        // do create new nodeState
        QNodeDefinition def = validator.getApplicableNodeDefinition(nodeInfo.getName(), nodeInfo.getNodeTypeName(), parent);
        if (def.isProtected()) {
            log.debug("Skipping protected nodeState (" + nodeInfo.getName() + ")");
            return null;
        } else {
            Operation an = AddNode.create(parent, nodeInfo.getName(), nodeInfo.getNodeTypeName(), nodeInfo.getId());
            stateMgr.execute(an);
            // retrieve id of state that has been created during execution of AddNode
            NodeId childId;
            List cne = parent.getChildNodeEntries(nodeInfo.getName());
            if (def.allowsSameNameSiblings()) {
                // TODO: find proper solution. problem with same-name-siblings
                childId = ((NodeState.ChildNodeEntry)cne.get(cne.size()-1)).getId();
            } else {
                childId = ((NodeState.ChildNodeEntry)cne.get(0)).getId();
            }
            NodeState nodeState = validator.getNodeState(childId);
            
            // and set mixin types
            PropertyId mixinPId = idFactory.createPropertyId(childId, QName.JCR_MIXINTYPES);
            Operation sm = SetMixin.create(mixinPId, nodeInfo.getMixinNames());
            stateMgr.execute(sm);
            return nodeState;
        }
    }

    /**
     *
     * @param pi
     * @param nodeState
     * @param nsResolver
     * @throws RepositoryException
     * @throws ConstraintViolationException
     */
    private void importProperty(PropInfo pi, NodeState nodeState, NamespaceResolver nsResolver) throws RepositoryException, ConstraintViolationException {
        QName propName = pi.getName();
        TextValue[] tva = pi.getValues();
        int infoType = pi.getType();

        PropertyState prop = null;
        QPropertyDefinition def;

        if (nodeState.hasPropertyName(propName)) {
            // a property with that name already exists...
            PropertyState existing = validator.getPropertyState(nodeState.getNodeId(), propName);
            def = existing.getDefinition();
            if (def.isProtected()) {
                // skip protected property
                log.debug("skipping protected property " + hierMgr.safeGetJCRPath(existing.getPropertyId()));
                return;
            }
            if (def.isAutoCreated()
                && (existing.getType() == infoType || infoType == PropertyType.UNDEFINED)
                && def.isMultiple() == existing.isMultiValued()) {
                // this property has already been auto-created, no need to create it
                prop = existing;
            } else {
                throw new ItemExistsException(hierMgr.safeGetJCRPath(existing.getPropertyId()));
            }
        } else {
            // there's no property with that name, find applicable definition
            if (tva.length == 1) {
                // could be single- or multi-valued (n == 1)
                def = validator.getApplicablePropertyDefinition(propName, infoType, nodeState);
            } else {
                // can only be multi-valued (n == 0 || n > 1)
                def = validator.getApplicablePropertyDefinition(propName, infoType, true, nodeState);
            }
            if (def.isProtected()) {
                // skip protected property
                log.debug("skipping protected property " + propName);
                return;
            }
        }

        // retrieve the target property type needed for creation of QValue(s)
        // including an eventual conversion. the targetType is then needed for
        // setting/updating the type of the property-state.
        int targetType = def.getRequiredType();
        if (targetType == PropertyType.UNDEFINED) {
            if (infoType == PropertyType.UNDEFINED) {
                targetType = PropertyType.STRING;
            } else {
                targetType = infoType;
            }
        }

        QValue[] values = getPropertyValues(pi, targetType, def.isMultiple(), nsResolver);
        if (prop == null) {
            // create new property
            PropertyId newPId = idFactory.createPropertyId(nodeState.getNodeId(), propName);
            Operation ap = AddProperty.create(newPId, targetType, def, values);
            stateMgr.execute(ap);
            prop = validator.getPropertyState(nodeState.getNodeId(), propName);
        } else {
            // modify value of existing property
            Operation sp = SetPropertyValue.create(prop, values, targetType);
            stateMgr.execute(sp);
        }

        // store reference for later resolution
        if (prop != null && prop.getType() == PropertyType.REFERENCE) {
            refTracker.processedReference(prop);
        }
    }

    /**
     *
     * @param propertyInfo
     * @param targetType
     * @param isMultiple
     * @param nsResolver
     * @return
     * @throws RepositoryException
     */
    private QValue[] getPropertyValues(PropInfo propertyInfo, int targetType, boolean isMultiple, NamespaceResolver nsResolver) throws RepositoryException {
        TextValue[] tva = propertyInfo.getValues();
        // check multi-valued characteristic
        if ((tva.length == 0 || tva.length > 1) && !isMultiple) {
            throw new ConstraintViolationException(propertyInfo.getName() + " is not multi-valued.");
        }
        // convert serialized values to QValue objects
        QValue[] iva = new QValue[tva.length];
        for (int i = 0; i < tva.length; i++) {
            iva[i] = buildQValue(tva[i], targetType, nsResolver);
        }
        return iva;
    }

    /**
     *
     * @param tv
     * @param targetType
     * @param nsResolver
     * @return
     * @throws RepositoryException
     */
    private QValue buildQValue(TextValue tv, int targetType, NamespaceResolver nsResolver) throws RepositoryException {
        QValue iv;
        try {
            switch (targetType) {
                case PropertyType.BINARY:
                    // base64 encoded BINARY type
                    if (tv.length() < 0x10000) {
                        // < 65kb: deserialize BINARY type in memory
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        Base64.decode(tv.retrieve(), baos);
                        // no need to close ByteArrayOutputStream
                        //baos.close();
                        iv = QValue.create(baos.toByteArray());
                    } else {
                        // >= 65kb: deserialize BINARY type
                        // using Reader and temporay file
                        TransientFileFactory fileFactory = TransientFileFactory.getInstance();
                        File tmpFile = fileFactory.createTransientFile("bin", null, null);
                        FileOutputStream out = new FileOutputStream(tmpFile);
                        Reader reader = tv.reader();
                        try {
                            Base64.decode(reader, out);
                        } finally {
                            reader.close();
                            out.close();
                        }
                        iv = QValue.create(tmpFile);
                    }
                    break;
                default:
                    // build iv using namespace context of xml document
                    Value v = ValueHelper.convert(tv.retrieve(), targetType, session.getValueFactory());
                    iv = ValueFormat.getQValue(v, nsResolver);
                    break;
            }
            return iv;
        } catch (IOException e) {
            String msg = "failed to retrieve serialized value";
            log.debug(msg, e);
            throw new RepositoryException(msg, e);
        }
    }
}