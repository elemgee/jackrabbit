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
package org.apache.jackrabbit.spi.commons.query;

/**
 * Defines the interface for a <code>QueryNodeVisitor</code>.
 */
public interface QueryNodeVisitor {

    Object visit(QueryRootNode node, Object data);

    Object visit(OrQueryNode node, Object data);

    Object visit(AndQueryNode node, Object data);

    Object visit(NotQueryNode node, Object data);

    Object visit(ExactQueryNode node, Object data);

    Object visit(NodeTypeQueryNode node, Object data);

    Object visit(TextsearchQueryNode node, Object data);

    Object visit(PathQueryNode node, Object data);

    Object visit(LocationStepQueryNode node, Object data);

    Object visit(RelationQueryNode node, Object data);

    Object visit(OrderQueryNode node, Object data);

    Object visit(DerefQueryNode node, Object data);

    Object visit(PropertyFunctionQueryNode node, Object data);
}
