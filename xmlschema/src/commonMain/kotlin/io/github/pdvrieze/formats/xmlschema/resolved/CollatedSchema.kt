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

import io.github.pdvrieze.formats.xmlschema.datatypes.impl.SingleLinkedList
import io.github.pdvrieze.formats.xmlschema.datatypes.primitiveInstances.VAnyURI
import io.github.pdvrieze.formats.xmlschema.datatypes.serialization.*
import io.github.pdvrieze.formats.xmlschema.impl.XmlSchemaConstants
import io.github.pdvrieze.formats.xmlschema.types.VDerivationControl
import io.github.pdvrieze.formats.xmlschema.types.VFormChoice
import nl.adaptivity.xmlutil.*

internal class SchemaData(
    val namespace: String?,
    val schemaLocation: String?,
    val elementFormDefault: VFormChoice?,
    val attributeFormDefault: VFormChoice?,
    val elements: Map<String, SchemaElement<XSGlobalElement>>,
    val attributes: Map<String, SchemaElement<XSGlobalAttribute>>,
    val types: Map<String, SchemaElement<XSGlobalType>>,
    val groups: Map<String, SchemaElement<XSGroup>>,
    val attributeGroups: Map<String, SchemaElement<XSAttributeGroup>>,
    val notations: Map<String, XSNotation>,
    val includedNamespaceToUri: Map<String, VAnyURI>,
    val knownNested: Map<String, SchemaData?>,
    val importedNamespaces: Set<String>,
) {

    constructor(namespace: String) : this(
        namespace = namespace,
        elementFormDefault = null,
        attributeFormDefault = null,
        schemaLocation = null,
        elements = emptyMap(),
        attributes = emptyMap(),
        types = emptyMap(),
        groups = emptyMap(),
        attributeGroups = emptyMap(),
        notations = emptyMap(),
        includedNamespaceToUri = emptyMap(),
        knownNested = emptyMap(),
        importedNamespaces = emptySet(),
    )

    constructor(
        namespace: String?,
        schemaLocation: String,
        elementFormDefault: VFormChoice?,
        attributeFormDefault: VFormChoice?,
        builder: DataBuilder
    ) : this(
        namespace = namespace,
        elementFormDefault = elementFormDefault,
        attributeFormDefault = attributeFormDefault,
        schemaLocation = schemaLocation,
        elements = builder.elements,
        attributes = builder.attributes,
        types = builder.types,
        groups = builder.groups,
        attributeGroups = builder.attributeGroups,
        notations = builder.notations,
        includedNamespaceToUri = builder.includedNamespaceToUri,
        knownNested = builder.newProcessed,
        importedNamespaces = builder.importedNamespaces,
    )

    fun findElement(elementName: QName): Pair<SchemaData, SchemaElement<XSGlobalElement>> {
        if (namespace == elementName.namespaceURI) { elements[elementName.localPart]?.let { return Pair(this, it) } }

        if (elementName.namespaceURI in importedNamespaces) {
            includedNamespaceToUri[elementName.namespaceURI]?.value?.let { uri ->
                knownNested[uri]?.let { n ->
                    n.elements[elementName.localPart]?.let { return Pair(n, it)}
                }
            }
        }
        throw IllegalArgumentException("No element with name $elementName found in schema")
    }

    fun findType(typeName: QName): SchemaElement<XSGlobalType>? {
        return when {
            namespace == typeName.namespaceURI -> types[typeName.localPart]
            typeName.namespaceURI in importedNamespaces -> {
                includedNamespaceToUri[typeName.namespaceURI]?.value?.let { uri ->
                    knownNested[uri]?.run { types[typeName.localPart] }
                }
            }

            else -> null
        }
    }

    fun findComplexType(typeName: QName): SchemaElement<XSGlobalComplexType>? {
        val t = findType(typeName)
        @Suppress("UNCHECKED_CAST")
        return when {
            t?.elem is XSGlobalComplexType -> t as SchemaElement<XSGlobalComplexType>
            else -> null
        }
    }

    fun findSimpleType(typeName: QName): SchemaElement<XSGlobalSimpleType>? {
        val t = findType(typeName)
        @Suppress("UNCHECKED_CAST")
        return when {
            t?.elem is XSGlobalSimpleType -> t as SchemaElement<XSGlobalSimpleType>
            else -> null
        }
    }

    fun checkRecursiveSubstitutionGroups() {
        val verifiedHeads = mutableSetOf<QName>()

        fun followChain(
            elementName: QName,
            seen: SingleLinkedList<QName>,
            elementInfo: Pair<SchemaData, SchemaElement<XSGlobalElement>> = findElement(elementName)
        ) {
            val (schema, schElem) = elementInfo
            val element = schElem.elem
            val newSeen = seen + elementName
            val sg = (element.substitutionGroup ?: run { verifiedHeads.addAll(newSeen); return })
                .let {
                    when (val ns = schema.namespace) {
                        null -> it
                        else -> it.map { QName(ns, it.localPart) }
                    }
                }

            for (referenced in sg) {
                if (referenced !in verifiedHeads) {
                    require(referenced !in newSeen) { "Recursive substitution group (${newSeen.sortedBy { it.toString() }.joinToString()})" }
                    followChain(referenced, newSeen)
                }
            }
        }

        for((name, childElement) in elements) {
            val qname = QName(namespace?:"", name)
            if (qname !in verifiedHeads) {
                followChain(qname, SingleLinkedList(), Pair(this, childElement))
            }
        }
    }


    fun checkRecursiveTypeDefinitions() {
        val verifiedSet = mutableSetOf<XSGlobalType>()
        for (typeInfo in (types.values)) {
            if (typeInfo.elem !in verifiedSet) { // skip already validated types
                val chain = mutableSetOf<XSGlobalType>()
                checkRecursiveTypes(Pair(this, typeInfo), verifiedSet, chain)
                verifiedSet.addAll(chain)
            }
        }
    }

    private fun checkRecursiveTypes(
        typeInfo: Pair<SchemaData, SchemaElement<out XSGlobalType>>,
        seenTypes: MutableSet<XSGlobalType>,
        inheritanceChain: MutableSet<XSGlobalType>,
    ) {
        val (schema, type) = typeInfo
        checkRecursiveTypes(type, schema, seenTypes, inheritanceChain)
    }

    private fun checkRecursiveTypes(
        startType: SchemaElement<out XSIType>,
        schema: SchemaData,
        seenTypes: MutableSet<XSGlobalType>,
        inheritanceChain: MutableSet<XSGlobalType>
    ) {
        require(startType.elem !is XSGlobalType || inheritanceChain.add(startType.elem)) {
            "Recursive type use for ${(startType.elem as XSGlobalType).name}: ${inheritanceChain.joinToString { it.name }}"
        }
        val refs: List<QName>
        val locals: List<XSLocalType>
        when (val st = startType.elem) {
            is XSISimpleType -> {
                when (val d = st.simpleDerivation) {
                    is XSSimpleList -> {
                        refs = listOfNotNull(d.itemTypeName)
                        locals = listOfNotNull(d.simpleType)
                    }

                    is XSSimpleRestriction -> {
                        refs = listOfNotNull(d.base)
                        locals = listOfNotNull(d.simpleType)
                    }

                    is XSSimpleUnion -> {
                        refs = d.memberTypes ?: emptyList()
                        locals = d.simpleTypes
                    }
                }
            }

            is XSComplexType.ComplexBase -> {
                when (val c: XSI_ComplexContent = st.content) {
                    is XSComplexType.Shorthand -> {
                        refs = listOfNotNull(c.base)
                        locals = emptyList()

                    }

                    is XSComplexContent -> {
                        refs = listOfNotNull(c.derivation.base)
                        locals = emptyList()

                    }

                    is XSSimpleContent -> {
                        refs = listOfNotNull(c.derivation.base)
                        locals = emptyList()
                    }
                }
            }

            is XSComplexType.Simple -> {
                val d = st.content.derivation
                refs = listOfNotNull(d.base)
                locals = listOfNotNull((d as? XSSimpleContentRestriction)?.simpleType)
            }

            else -> throw AssertionError("Unreachable")
        }
        val finalRefs = refs.asSequence()
            .filter { it.namespaceURI != XmlSchemaConstants.XS_NAMESPACE && it.namespaceURI != XmlSchemaConstants.XSI_NAMESPACE }
            .let {
                when (val ns = schema.namespace) {
                    null -> it
                    else -> it.map { QName(ns, it.localPart) }
                }
            }.toSet()

        for (ref in finalRefs) {
            val typeInfo = when {
                startType is SchemaElement.Redefined<*> && ref.isEquivalent(startType.elementName)->
                    requireNotNull(startType.baseSchema.findType(ref)) { "Failure to find redefined type $ref" }
                else -> requireNotNull(findType(ref)) { "Failure to find referenced type $ref" }
            }

            if (typeInfo.elem !in seenTypes) {
                checkRecursiveTypes(Pair(this, typeInfo), seenTypes, inheritanceChain)
            }

        }
        for (local in locals) {
            checkRecursiveTypes(SchemaElement(local, schema.schemaLocation?:""), schema, seenTypes, inheritanceChain)
        }

    }


    class DataBuilder(processed: Map<String, SchemaData?>? = null) {
        val elements: MutableMap<String, SchemaElement<XSGlobalElement>> = mutableMapOf()
        val attributes: MutableMap<String, SchemaElement<XSGlobalAttribute>> = mutableMapOf()
        val types: MutableMap<String, SchemaElement<XSGlobalType>> = mutableMapOf()
        val groups: MutableMap<String, SchemaElement<XSGroup>> = mutableMapOf()
        val attributeGroups: MutableMap<String, SchemaElement<XSAttributeGroup>> = mutableMapOf()
        val notations: MutableMap<String, XSNotation> = mutableMapOf()
        val includedNamespaceToUri: MutableMap<String, VAnyURI> = mutableMapOf()
        val newProcessed: MutableMap<String, SchemaData?> = processed?.toMutableMap() ?: mutableMapOf()
        val importedNamespaces: MutableSet<String> = mutableSetOf()

        fun addFromSchema(sourceSchema: XSSchema, schemaLocation: String, targetNamespace: String?) {
            val chameleon = when {
                ! sourceSchema.targetNamespace.isNullOrEmpty() -> null
                targetNamespace.isNullOrEmpty() -> error("Invalid name override to default namespace")
                else -> targetNamespace
            }
            sourceSchema.elements.associateToUnique(elements) { it.name.toString() to SchemaElement.auto(it, schemaLocation, sourceSchema, chameleon) }
            sourceSchema.attributes.associateToUnique(attributes) { it.name.toString() to SchemaElement.auto(it, schemaLocation, sourceSchema, chameleon) }
            sourceSchema.simpleTypes.associateToUnique(types) { it.name.toString() to SchemaElement.auto(it, schemaLocation, sourceSchema, chameleon) }
            sourceSchema.complexTypes.associateToUnique(types) { it.name.toString() to SchemaElement.auto(it, schemaLocation, sourceSchema, chameleon) }
            sourceSchema.groups.associateToUnique(groups) { it.name.toString() to SchemaElement.auto(it, schemaLocation, sourceSchema, chameleon) }
            sourceSchema.attributeGroups.associateToUnique(attributeGroups) { it.name.toString() to SchemaElement.auto(it, schemaLocation, sourceSchema, chameleon) }
            sourceSchema.notations.associateToUnique(notations) { it.name.toString() to it }
        }

        fun addInclude(sourceData: SchemaData, targetNamespace: String?) {
            val chameleon = when {
                sourceData.namespace.isNullOrEmpty() -> null
                targetNamespace.isNullOrEmpty() -> error("Invalid name override to default namespace")
                targetNamespace != sourceData.namespace -> targetNamespace
                else -> null
            }

            if (chameleon == null) {
                sourceData.elements.addUnique(elements)
                sourceData.groups.addUnique(groups)
                sourceData.attributes.addUnique(attributes)
                sourceData.types.addUnique(types)
                sourceData.attributeGroups.addUnique(attributeGroups)
                sourceData.notations.addUnique(notations)
            } else {
                sourceData.elements.addUnique(elements.mapValuesTo(mutableMapOf()) { (k, v) -> v.toChameleon(chameleon, sourceData) })
                sourceData.groups.addUnique(groups.mapValuesTo(mutableMapOf()) { (k, v) -> v.toChameleon(chameleon, sourceData) })
                sourceData.attributes.addUnique(attributes.mapValuesTo(mutableMapOf()) { (k, v) -> v.toChameleon(chameleon, sourceData) })
                sourceData.types.addUnique(types.mapValuesTo(mutableMapOf()) { (k, v) -> v.toChameleon(chameleon, sourceData) })
                sourceData.attributeGroups.addUnique(attributeGroups.mapValuesTo(mutableMapOf()) { (k, v) -> v.toChameleon(chameleon, sourceData) })
                sourceData.notations.addUnique(notations)
            }
            sourceData.schemaLocation?.let { newProcessed[it] = sourceData }
            importedNamespaces.addAll(sourceData.importedNamespaces)
        }

    }


    companion object {

        /**
         * Create a new schema data object
         *
         * @param sourceSchema The schema to make into data
         * @param schemaLocation The uri location for this particular schema file
         * @param targetNamespace The target namespace for the file
         * @param resolver The resolver responsible for resolving the files. It is relative to the currently processed file
         * @param alreadyProcessed A map from uri's to schema data that has already been processed (somewhat)
         */
        operator fun invoke(
            sourceSchema: XSSchema,
            schemaLocation: String,
            targetNamespace: String?,
            resolver: ResolvedSchema.Resolver,
            alreadyProcessed: Map<String, SchemaData?>
        ): SchemaData {
            val b = DataBuilder(alreadyProcessed)
            b.newProcessed.put(schemaLocation, null)

            b.addFromSchema(sourceSchema, schemaLocation, targetNamespace)

            for (include in sourceSchema.includes) {
                val includeLocation = resolver.resolve(include.schemaLocation)
                val includeData: SchemaData = when {
                    includeLocation.value in alreadyProcessed ->
                        requireNotNull(alreadyProcessed[includeLocation.value]) { "Recursive includes: $includeLocation" }

                    else -> {
                        val delegateResolver = resolver.delegate(includeLocation)
                        val parsed = resolver.readSchema(includeLocation)
                        require(parsed.targetNamespace.let { it == null || it.value == targetNamespace })
                        SchemaData(
                            parsed,
                            includeLocation.value,
                            targetNamespace,
                            delegateResolver,
                            b.newProcessed
                        ).also {
                            b.newProcessed[includeLocation.value] = it
                        }
                    }
                }

                b.addInclude(includeData, targetNamespace)
            }

            for (redefine in sourceSchema.redefines) {
                val redefineLocation = resolver.resolve(redefine.schemaLocation)
                val redefineData: SchemaData = when {
                    redefineLocation.value in alreadyProcessed ->
                        requireNotNull(alreadyProcessed[redefineLocation.value]) { "Recursive redefines: $redefineLocation" }

                    else -> {
                        val delegateResolver = resolver.delegate(redefineLocation)
                        val parsed = resolver.readSchema(redefineLocation)
                        require(parsed.targetNamespace.let { it == null || it.value == targetNamespace })
                        SchemaData(
                            parsed,
                            redefineLocation.value,
                            targetNamespace,
                            delegateResolver,
                            b.newProcessed
                        ).also {
                            b.newProcessed[redefineLocation.value] = it
                        }
                    }
                }


                b.addInclude(redefineData, targetNamespace)

                val redefinedTypeNames = mutableSetOf<String>()
                for (st in (redefine.simpleTypes + redefine.complexTypes)) {
                    val name = st.name.xmlString
                    require(redefinedTypeNames.add(name)) { "Redefine redefines the same type multiple times" }
                    val baseType = requireNotNull(b.types[name]) { "Redefine must actually redefine type" }
                    // TODO add check for base type
                    val typeName = QName(targetNamespace ?: "", name)
                    b.types[name] = SchemaElement.Redefined(st, redefineData, schemaLocation, typeName, Redefinable.TYPE)
                }

                val redefinedGroups = mutableSetOf<String>()
                for (g in redefine.groups) {
                    val name = g.name.xmlString
                    require(redefinedGroups.add(name)) { "Redefine redefines the same group multiple times" }
                    val oldGroup = requireNotNull(b.groups[name]) { "Redefine must actually redefine group" }
                    // TODO add checks if needed
                    val groupName = QName(targetNamespace ?: "", name)
                    b.groups[name] = SchemaElement.Redefined(g, redefineData, schemaLocation, groupName, Redefinable.GROUP)
                }

                val redefinedAttrGroups = mutableSetOf<String>()
                for (ag in redefine.attributeGroups) {
                    val name = ag.name.xmlString
                    require(redefinedAttrGroups.add(name)) { "Redefine redefines the same attribute group multiple times" }
                    val oldGroup =
                        requireNotNull(b.attributeGroups[name]) { "Redefine must actually redefine attribute group" }
                    // TODO add checks if needed
                    val agName = QName(targetNamespace ?: "", name)

                    b.attributeGroups[name] = SchemaElement.Redefined(ag, redefineData, schemaLocation, agName, Redefinable.ATTRIBUTEGROUP)
                }
            }

            for (import in sourceSchema.imports) {
                val il = import.schemaLocation
                if (il == null) {
                    val ns =
                        requireNotNull(import.namespace) { "import must specify at least namespace or location" }
                    b.includedNamespaceToUri[ns.value] = VAnyURI("")
                    b.importedNamespaces.add(ns.value)
                } else {
                    val importLocation = resolver.resolve(il)

                    val actualImport: SchemaData? = when {
                        // imports can be delayed in parsing
                        importLocation.value in alreadyProcessed -> alreadyProcessed[importLocation.value]

                        else -> {
                            val delegateResolver = resolver.delegate(importLocation)
                            val parsed = resolver.readSchema(importLocation)
                            val actualNamespace = when (val ins = import.namespace) {
                                null -> requireNotNull(parsed.targetNamespace) { "Missing namespace for import" }
                                else -> {
                                    require(parsed.targetNamespace == null || parsed.targetNamespace == ins) {
                                        "Imports cannot change source namespace from ${parsed.targetNamespace} to $ins"
                                    }
                                    ins
                                }
                            }

                            require(parsed.targetNamespace.let { it == null || it == import.namespace }) { "import namespaces must meet requirements '$targetNamespace' ← '${parsed.targetNamespace}'" }
                            b.importedNamespaces.add(actualNamespace.value)
                            b.includedNamespaceToUri[actualNamespace.value] = importLocation

                            SchemaData(
                                parsed,
                                importLocation.value,
                                actualNamespace.value,
                                delegateResolver,
                                b.newProcessed
                            ).also {
                                b.newProcessed[importLocation.value] = it
                            }
                        }
                    }
                    if (actualImport != null) b.newProcessed[importLocation.value] = actualImport
                }
            }

            // TODO add override support

            return SchemaData(
                namespace = targetNamespace,
                schemaLocation = schemaLocation,
                elementFormDefault = sourceSchema.elementFormDefault,
                attributeFormDefault = sourceSchema.attributeFormDefault,
                builder = b
            )
        }

    }
}

class ChameleonWrapper internal constructor(
    override val attributeFormDefault: VFormChoice = VFormChoice.UNQUALIFIED,
    override val elementFormDefault: VFormChoice = VFormChoice.UNQUALIFIED,
    val base: ResolvedSchemaLike,
    val chameleonNamespace: VAnyURI?
) : ResolvedSchemaLike() {

    override val version: ResolvedSchema.Version get() = base.version

    override val targetNamespace: VAnyURI?
        get() = chameleonNamespace

    override fun hasLocalTargetNamespace(): Boolean {
        return chameleonNamespace.isNullOrEmpty()
    }

    override val blockDefault: Set<VDerivationControl.T_BlockSetValues>
        get() = base.blockDefault
    override val finalDefault: Set<VDerivationControl.Type>
        get() = base.finalDefault
    override val defaultOpenContent: XSDefaultOpenContent?
        get() = base.defaultOpenContent
    override val defaultAttributes: QName? get() = base.defaultAttributes

    private fun QName.extend(): QName {
        return when {
            namespaceURI.isEmpty() -> QName(chameleonNamespace?.value ?: "", localPart, prefix)
            else -> this
        }
    }

    override fun maybeSimpleType(typeName: QName): ResolvedGlobalSimpleType? {
        return base.maybeSimpleType(typeName.extend())
    }

    override fun maybeType(typeName: QName): ResolvedGlobalType? {
        return base.maybeType(typeName.extend())
    }

    override fun maybeAttribute(attributeName: QName): ResolvedGlobalAttribute? {
        return base.maybeAttribute(attributeName.extend())
    }

    override fun maybeAttributeGroup(attributeGroupName: QName): ResolvedGlobalAttributeGroup? {
        return base.maybeAttributeGroup(attributeGroupName.extend())
    }

    override fun maybeGroup(groupName: QName): ResolvedGlobalGroup? {
        return base.maybeGroup(groupName.extend())
    }

    override fun maybeElement(elementName: QName): ResolvedGlobalElement? {
        return base.maybeElement(elementName.extend())
    }

    override fun maybeIdentityConstraint(constraintName: QName): ResolvedIdentityConstraint? {
        return base.maybeIdentityConstraint(constraintName)
    }

    override fun maybeNotation(notationName: QName): ResolvedNotation? {
        return base.maybeNotation(notationName)
    }

    override fun substitutionGroupMembers(headName: QName): Set<ResolvedGlobalElement> {
        return base.substitutionGroupMembers(headName)
    }

    override fun toString(): String {
        return "ChameleonWrapper($chameleonNamespace)"
    }


}


internal class RedefineSchema(
    val base: ResolvedSchemaLike,
    val data: SchemaData,
    internal val elementName: QName,
    internal val elementKind: Redefinable,
    override val blockDefault: Set<VDerivationControl.T_BlockSetValues> = emptySet(),
    override val finalDefault: Set<VDerivationControl.Type> = emptySet(),
    override val defaultOpenContent: XSDefaultOpenContent? = null,
    override val defaultAttributes: QName? = null,
) : ResolvedSchemaLike() {

    override val version: ResolvedSchema.Version get() = base.version

    override val targetNamespace: VAnyURI? get() = data.namespace?.let(::VAnyURI) ?: base.targetNamespace
    private val originalNS get() = targetNamespace?.value ?: ""

    override fun hasLocalTargetNamespace(): Boolean {
        return targetNamespace.isNullOrEmpty()
    }

    override val attributeFormDefault: VFormChoice
        get() = data.attributeFormDefault ?: VFormChoice.UNQUALIFIED
    override val elementFormDefault: VFormChoice
        get() = data.elementFormDefault ?: VFormChoice.UNQUALIFIED

    override fun maybeSimpleType(typeName: QName): ResolvedGlobalSimpleType? {
        if (elementKind == Redefinable.TYPE && elementName == typeName) {
            return nestedSimpleType(typeName)
        }

        return base.maybeSimpleType(typeName)
    }

    override fun maybeType(typeName: QName): ResolvedGlobalType? {
        if (elementKind == Redefinable.TYPE && elementName == typeName) {
            return nestedType(typeName)
        }

        return base.maybeType(typeName)
    }

    fun nestedSimpleType(typeName: QName): ResolvedGlobalSimpleType {
        require(originalNS == typeName.namespaceURI)
        val t = data.findType(typeName)
        if (t != null && t.elem is XSGlobalSimpleType) {
            return ResolvedGlobalSimpleType(t as SchemaElement<XSGlobalSimpleType>, t.effectiveSchema(this))
        }
        error("Nested simple type with name $typeName could not be found")
    }

    fun nestedComplexType(typeName: QName): ResolvedGlobalComplexType {
        require(originalNS == typeName.namespaceURI)
        val t = data.findComplexType(typeName) ?: error("No nested complex type with name $typeName")
        return ResolvedGlobalComplexType(t, t.effectiveSchema(this))
    }

    fun nestedType(typeName: QName): ResolvedGlobalType {
        require(originalNS == typeName.namespaceURI)

        val t = data.findType(typeName)
        if (t != null) {
            if (t.elem is XSGlobalComplexType) {
                return ResolvedGlobalComplexType(t.elem, t.effectiveSchema(this), data.schemaLocation ?: "")
            } else if (t.elem is XSGlobalSimpleType) {
                return ResolvedGlobalSimpleType(t.elem, t.effectiveSchema(this))
            }
        }
        error("No nested complex type with name $typeName")
    }

    fun nestedAttributeGroup(typeName: QName): ResolvedGlobalAttributeGroup {
        require(originalNS == typeName.namespaceURI) { "Redefine namespace mismatch. Nested ns: $originalNS, name: $typeName" }

        val localName = typeName.localPart
        val ag = data.attributeGroups[localName] ?: error("No nested complex type with name $typeName")

        return ResolvedGlobalAttributeGroup(ag, ag.effectiveSchema(this))
    }

    fun nestedGroup(typeName: QName): ResolvedGlobalGroup {
        require(originalNS == typeName.namespaceURI) { }
        val localName = typeName.localPart
        val g = data.groups[localName] ?: error("No nested complex type with name $typeName")

        return ResolvedGlobalGroup(g, g.effectiveSchema(this))
    }

    fun nestedElement(typeName: QName): ResolvedGlobalElement {
        require(originalNS == typeName.namespaceURI) { }
        val localName = typeName.localPart
        val e = data.elements[localName] ?: error("No nested complex type with name $typeName")

        return ResolvedGlobalElement(e, this)
    }

    fun nestedAttribute(typeName: QName): ResolvedGlobalAttribute {
        require(originalNS == typeName.namespaceURI) { }
        val localName = typeName.localPart
        val a = data.attributes[localName] ?: error("No nested complex type with name $typeName")

        return ResolvedGlobalAttribute(a, this)
    }

    override fun maybeAttributeGroup(attributeGroupName: QName): ResolvedGlobalAttributeGroup? {
        if (elementKind == Redefinable.ATTRIBUTEGROUP && elementName == attributeGroupName) {
            return nestedAttributeGroup(attributeGroupName)
        }
        return base.maybeAttributeGroup(attributeGroupName)
    }

    override fun maybeGroup(groupName: QName): ResolvedGlobalGroup? {
        if (elementKind == Redefinable.GROUP && elementName == groupName) {
            return nestedGroup(groupName)
        }
        return base.maybeGroup(groupName)
    }

    override fun maybeElement(elementName: QName): ResolvedGlobalElement? {
        if (elementKind == Redefinable.ELEMENT && this.elementName == elementName) {
            return nestedElement(elementName)
        }
        return base.maybeElement(elementName)
    }

    override fun maybeAttribute(attributeName: QName): ResolvedGlobalAttribute? {
        if (elementKind == Redefinable.ATTRIBUTE && elementName == attributeName) {
            return nestedAttribute(attributeName)
        }
        return base.maybeAttribute(attributeName)
    }

    override fun maybeIdentityConstraint(constraintName: QName): ResolvedIdentityConstraint? {
        return base.maybeIdentityConstraint(constraintName)
    }

    override fun maybeNotation(notationName: QName): ResolvedNotation? {
        return base.maybeNotation(notationName)
    }

    override fun substitutionGroupMembers(headName: QName): Set<ResolvedGlobalElement> {
        return base.substitutionGroupMembers(headName)
    }

}

internal enum class Redefinable { TYPE, ELEMENT, ATTRIBUTE, GROUP, ATTRIBUTEGROUP }

private inline fun <T, K, V, M : MutableMap<in K, in V>> Iterable<T>.associateToUnique(
    destination: M,
    keySelector: (T) -> Pair<K, V>
): M {
    for (element in this) {
        val (key, value) = keySelector(element)
        require(key !in destination) { "Duplicate key on unique association" }
        destination.put(key, value)
    }
    return destination
}

private inline fun <K, V, M : MutableMap<in K, in V>> Map<K, V>.addUnique(
    destination: M
): M {
    for ((key, value) in this) {
        require(key !in destination) { "Duplicate key on unique association" }
        destination.put(key, value)
    }
    return destination
}

internal sealed class SchemaElement<T>(val elem: T, val schemaLocation: String) {
    abstract fun effectiveSchema(schema: ResolvedSchemaLike): ResolvedSchemaLike
    abstract fun toChameleon(chameleon: String, attributeFormDefault: VFormChoice?, elementFormDefault: VFormChoice?): Chameleon<T>
    fun toChameleon(chameleon: String, schemaData: SchemaData): Chameleon<T> =
        toChameleon(chameleon, schemaData.attributeFormDefault, schemaData.elementFormDefault)

    class Direct<T>(type: T, schemaLocation: String) : SchemaElement<T>(type, schemaLocation) {
        override fun effectiveSchema(schema: ResolvedSchemaLike): ResolvedSchemaLike = schema

        override fun toChameleon(chameleon: String, attributeFormDefault: VFormChoice?, elementFormDefault: VFormChoice?): Chameleon<T> {
            return Chameleon(
                elem = elem,
                schemaLocation = schemaLocation,
                attributeFormDefault = attributeFormDefault ?: VFormChoice.UNQUALIFIED,
                elementFormDefault = elementFormDefault ?: VFormChoice.UNQUALIFIED,
                newNS = chameleon
            )
        }

        override fun toString(): String = "d($elem)"
    }

    class Chameleon<T>(
        elem: T,
        schemaLocation: String,
        val attributeFormDefault: VFormChoice,
        val elementFormDefault: VFormChoice,
        val newNS: String
    ) : SchemaElement<T>(elem, schemaLocation) {

        constructor(
            elem: T,
            schemaLocation: String,
            baseSchema: XSSchema,
            newNS: String
        ) : this(
            elem,
            schemaLocation,
            baseSchema.attributeFormDefault ?: VFormChoice.UNQUALIFIED,
            baseSchema.elementFormDefault ?: VFormChoice.UNQUALIFIED,
            newNS
        )

        override fun toChameleon(chameleon: String, attributeFormDefault: VFormChoice?, elementFormDefault: VFormChoice?): Chameleon<T> {
            return Chameleon(elem, schemaLocation, this.attributeFormDefault, this.elementFormDefault, chameleon)
        }

        override fun effectiveSchema(schema: ResolvedSchemaLike): ResolvedSchemaLike {
            return ChameleonWrapper(
                attributeFormDefault = attributeFormDefault,
                elementFormDefault = elementFormDefault,
                base = schema,
                chameleonNamespace = VAnyURI(newNS)
            )
        }
        override fun toString(): String = "chameleon($newNS, $elem)"
    }

    class Redefined<T>(
        elem: T,
        val baseSchema: SchemaData,
        schemaLocation: String,
        val elementName: QName,
        val elementKind: Redefinable
    ) : SchemaElement<T>(elem, schemaLocation) {
        override fun effectiveSchema(schema: ResolvedSchemaLike): ResolvedSchemaLike = when {
            // handle the case where it is called multiple times
            schema is RedefineSchema && schema.data == baseSchema -> schema
            else -> RedefineSchema(schema, baseSchema, elementName, elementKind)
        }

        override fun toChameleon(
            chameleon: String,
            attributeFormDefault: VFormChoice?,
            elementFormDefault: VFormChoice?
        ): Chameleon<T> {
            throw UnsupportedOperationException("Redefined elements can not be chameleons")
        }

        override fun toString(): String = "redefine($elem)"
    }

    companion object {
        inline operator fun <T> invoke(elem: T, schemaLocation: String): Direct<T> = Direct(elem, schemaLocation)
        inline fun <T> auto(elem: T, schemaLocation: String, baseSchema: XSSchema, chameleonNs: String?): SchemaElement<T> = when(chameleonNs) {
            null -> Direct(elem, schemaLocation)
            else -> Chameleon(elem, schemaLocation, baseSchema, chameleonNs)
        }
    }
}

internal data class SchemaAssociatedElement<T>(val schemaLocation: String, val element: T)
