/*
 * Copyright (c) 2024.
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

package nl.adaptivity.xmlutil.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.modules.SerializersModule
import nl.adaptivity.xmlutil.Namespace
import nl.adaptivity.xmlutil.QName
import nl.adaptivity.xmlutil.core.impl.multiplatform.computeIfAbsent
import nl.adaptivity.xmlutil.localPart
import nl.adaptivity.xmlutil.namespaceURI
import nl.adaptivity.xmlutil.serialization.structure.SafeParentInfo
import nl.adaptivity.xmlutil.serialization.structure.XmlCompositeDescriptor
import nl.adaptivity.xmlutil.serialization.structure.XmlDescriptor
import nl.adaptivity.xmlutil.serialization.structure.XmlTypeDescriptor

/**
 * Opaque caching class that allows for caching format related data (to speed up reuse). This is
 * intended to be stored on the config, thus reused through multiple serializations.
 * Note that this requires the `serialName` attribute of `SerialDescriptor` instances to be unique.
 */
internal class DefaultFormatCache: FormatCache() {
    private val typeDescCache = HashMap<TypeKey, XmlTypeDescriptor>()
    private val elemDescCache = HashMap<DescKey, XmlDescriptor>()
    private val pendingDescs = HashSet<DescKey>()

    @OptIn(ExperimentalSerializationApi::class)
    override fun lookupType(namespace: Namespace?, serialDesc: SerialDescriptor, defaultValue: () -> XmlTypeDescriptor): XmlTypeDescriptor {
        return lookupType(TypeKey(namespace?.namespaceURI, serialDesc.serialName), serialDesc.kind, defaultValue)
    }

    /**
     * Lookup a type descriptor for this type with the given namespace.
     * @param parentName A key
     */
    @OptIn(ExperimentalSerializationApi::class)
    override fun lookupType(parentName: QName, serialDesc: SerialDescriptor, defaultValue: () -> XmlTypeDescriptor): XmlTypeDescriptor {
        return lookupType(TypeKey(parentName.namespaceURI, serialDesc.serialName), serialDesc.kind, defaultValue)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun lookupType(name: QName, kind: SerialKind, defaultValue: () -> XmlTypeDescriptor): XmlTypeDescriptor {
        return lookupType(TypeKey(name.namespaceURI, name.localPart), kind, defaultValue)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun lookupType(name: TypeKey, kind: SerialKind, defaultValue: () -> XmlTypeDescriptor): XmlTypeDescriptor {
        return when (kind) {
            StructureKind.MAP,
            StructureKind.LIST -> defaultValue()

            else -> typeDescCache.getOrPut(name, defaultValue)
        }
    }

    override fun lookupDescriptor(
        overridenSerializer: KSerializer<*>?,
        serializerParent: SafeParentInfo,
        tagParent: SafeParentInfo,
        canBeAttribute: Boolean,
        defaultValue: () -> XmlDescriptor
    ): XmlDescriptor {
        val key = DescKey(overridenSerializer, serializerParent, tagParent.takeIf { it !== serializerParent }, canBeAttribute)

        check(pendingDescs.add(key)) {
            "Recursive lookup of ${serializerParent.elementSerialDescriptor.serialName}"
        }

        // This has to be getOrPut rather than `computeIfAbsent` as computeIfAbsent prevents other
        // changes to different types. GetOrPut does not have that property (but is technically slower)
        return elemDescCache.getOrPut(key) {
//            val parentName = serializerParent.descriptor?.typeDescriptor?.run { typeQname ?: serialName }
//            println("Calculating new descriptor for $parentName/${serializerParent.elementSerialDescriptor.serialName}")
            defaultValue()
        }.also {
            pendingDescs.remove(key)
        }
    }

    override fun getCompositeDescriptor(
        config: XmlConfig,
        serializersModule: SerializersModule,
        serializerParent: SafeParentInfo,
        tagParent: SafeParentInfo,
        preserveSpace: Boolean
    ): XmlCompositeDescriptor {
        return XmlCompositeDescriptor(config, serializersModule, serializerParent, tagParent, preserveSpace)
//        return lookupDescriptor(null, serializerParent, tagParent, false) {
//            XmlCompositeDescriptor(config, serializersModule, serializerParent, tagParent, preserveSpace)
//        } as XmlCompositeDescriptor
    }

    internal data class DescKey(
        val overridenSerializer: KSerializer<*>?,
        val serializerParent: SafeParentInfo,
        val tagParent: SafeParentInfo?,
        val canBeAttribute: Boolean
    )

    private data class TypeKey(val namespace: String, val serialName: String) {
        constructor(namespace: String?, serialName: String, dummy: Boolean = false) : this(namespace ?: "", serialName)
    }
}
