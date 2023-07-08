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

import io.github.pdvrieze.formats.xmlschema.datatypes.AnyType
import io.github.pdvrieze.formats.xmlschema.datatypes.impl.SingleLinkedList
import io.github.pdvrieze.formats.xmlschema.datatypes.primitiveInstances.VID
import io.github.pdvrieze.formats.xmlschema.datatypes.serialization.*
import io.github.pdvrieze.formats.xmlschema.types.T_Assertion
import io.github.pdvrieze.formats.xmlschema.types.T_ComplexDerivation
import nl.adaptivity.xmlutil.QName

sealed class ResolvedDerivation(scope: ResolvedComplexType, override val schema: ResolvedSchemaLike) :
    T_ComplexDerivation,
    ResolvedPart {
    abstract override val rawPart: XSComplexContent.XSComplexDerivationBase

    val term: ResolvedComplexType.ResolvedDirectParticle<*>? by lazy {
        when (val t = rawPart.term) {
            is XSAll -> ResolvedAll(scope, t, schema)
            is XSChoice -> ResolvedChoice(scope, t, schema)
//            is XSGroupRefParticle -> ResolvedGroupRefParticle(t, schema)
            is XSSequence -> ResolvedSequence(scope, t, schema)
            null -> null
        }
    }

    final override val asserts: List<T_Assertion> get() = rawPart.asserts
    abstract override val attributes: List<ResolvedLocalAttribute>
    abstract override val attributeGroups: List<ResolvedAttributeGroupRef>
    final override val anyAttribute: XSAnyAttribute? get() = rawPart.anyAttribute
    final override val annotation: XSAnnotation? get() = rawPart.annotation
    final override val id: VID? get() = rawPart.id
    final override val otherAttrs: Map<QName, String> get() = rawPart.otherAttrs
    final override val base: QName? get() = rawPart.base
    final override val openContent: XSOpenContent? get() = rawPart.openContent

    val baseType: ResolvedGlobalType by lazy {
        schema.type(base ?: AnyType.qName)
    }

    open fun check(seenTypes: SingleLinkedList<QName>, inheritedTypes: SingleLinkedList<QName>) {
        super<ResolvedPart>.check()
        val b = base
        if (b != null && b !in seenTypes) { // Recursion is allowed, but must be managed
            baseType.check(seenTypes, inheritedTypes)
        }

        term?.check()
        attributes.forEach(ResolvedLocalAttribute::check)
        attributeGroups.forEach(ResolvedAttributeGroupRef::check)

    }
}
