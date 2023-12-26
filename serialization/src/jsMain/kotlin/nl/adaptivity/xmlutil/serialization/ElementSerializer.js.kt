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

package nl.adaptivity.xmlutil.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import nl.adaptivity.xmlutil.QName
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlSerializer
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.core.impl.idom.IDocument
import nl.adaptivity.xmlutil.core.impl.idom.IElement
import nl.adaptivity.xmlutil.util.impl.createDocument
import nl.adaptivity.xmlutil.dom.Element as Element1
import nl.adaptivity.xmlutil.dom2.Element as Element2

public actual object ElementSerializer : XmlSerializer<Element1> {
    private val delegate = Element2.serializer() as XmlSerializer<Element2>
    private val helperDoc = createDocument(QName("XX")) as IDocument

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor get() = SerialDescriptor("org.w3c.dom.Element", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: Element1) {
        val e = value as? Element2 ?: (helperDoc.adoptNode(value) as Element2)
        return delegate.serialize(encoder, e)
    }

    override fun serializeXML(encoder: Encoder, output: XmlWriter, value: Element1, isValueChild: Boolean) {
        val e = value as? Element2 ?: (helperDoc.adoptNode(value) as Element2)
        return delegate.serializeXML(encoder, output, e, isValueChild)
    }

    override fun deserialize(decoder: Decoder): IElement {
        return delegate.deserialize(decoder) as IElement
    }

    override fun deserializeXML(
        decoder: Decoder,
        input: XmlReader,
        previousValue: Element1?,
        isValueChild: Boolean
    ): IElement {
        return delegate.deserializeXML(decoder, input, previousValue as Element2, isValueChild) as IElement
    }
}
