/*
 * Copyright (c) 2023.
 *
 * This file is part of xmlutil.
 *
 * This file is licenced to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You should have received a copy of the license with the source distribution.
 * Alternatively, you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.github.pdvrieze.formats.xmlschema.resolved

import io.github.pdvrieze.formats.xmlschema.datatypes.primitiveInstances.VNCName
import io.github.pdvrieze.formats.xmlschema.datatypes.serialization.XSField
import io.github.pdvrieze.formats.xmlschema.datatypes.serialization.XSKey
import io.github.pdvrieze.formats.xmlschema.datatypes.serialization.XSSelector
import io.github.pdvrieze.formats.xmlschema.model.IdentityConstraintModel
import io.github.pdvrieze.formats.xmlschema.types.T_Key
import nl.adaptivity.xmlutil.QName
import nl.adaptivity.xmlutil.qname

class ResolvedDirectKey(
    override val rawPart: XSKey,
    schema: ResolvedSchemaLike,
    owner: ResolvedElement,
): ResolvedNamedIdentityConstraint(schema, owner), T_Key, ResolvedKey {
    override val name: VNCName = checkNotNull(rawPart.name)

    val qName: QName get() = qname(schema.targetNamespace?.value, name.xmlString)

    override val selector: XSSelector get() = rawPart.selector

    override val fields: List<XSField> get() = rawPart.fields

    override val mdlIdentityConstraintCategory: IdentityConstraintModel.Category
        get() = IdentityConstraintModel.Category.KEY

    override val constraint: ResolvedDirectKey
        get() = this

    override fun check() {
        super<ResolvedNamedIdentityConstraint>.check()
        checkNotNull(rawPart.name)
    }

}
