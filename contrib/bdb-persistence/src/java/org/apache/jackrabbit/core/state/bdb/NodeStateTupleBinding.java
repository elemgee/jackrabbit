/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.state.bdb;

import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.util.Serializer;

public class NodeStateTupleBinding extends TupleBinding {

    private Log log = LogFactory.getLog(NodeStateTupleBinding.class);

    private NodeId id;

    public NodeStateTupleBinding(NodeId nodeId) {
        this.id = nodeId;
    }

    public NodeStateTupleBinding() {
    }

    public Object entryToObject(TupleInput in) {

        NodeState state = new NodeState(id, null, null, NodeState.STATUS_NEW, false);

        try {
            Serializer.deserialize(state, in);
        } catch (Exception e) {
            // since the TupleInput methods do not throw any
            // exceptions the above call should neither...
            String msg = "error while deserializing node state";
            log.debug(msg);
            throw new RuntimeException(msg, e);
        }

        return state;
    }

    public void objectToEntry(Object o, TupleOutput out) {
        try {
            Serializer.serialize((NodeState) o, out);
        } catch (Exception e) {
            // since the TupleOutput methods do not throw any
            // exceptions the above call should neither...
            String msg = "error while serializing node state";
            log.debug(msg);
            throw new RuntimeException(msg, e);
        }
    }

}
