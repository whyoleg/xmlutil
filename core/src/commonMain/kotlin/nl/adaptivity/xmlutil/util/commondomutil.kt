/*
 * Copyright (c) 2022.
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

package nl.adaptivity.xmlutil.util

import nl.adaptivity.xmlutil.QName
import nl.adaptivity.xmlutil.XMLConstants
import nl.adaptivity.xmlutil.dom.*
import nl.adaptivity.xmlutil.toCName
import nl.adaptivity.xmlutil.dom.Attr as Attr1
import nl.adaptivity.xmlutil.dom.Element as Element1
import nl.adaptivity.xmlutil.dom.NamedNodeMap as NamedNodeMap1
import nl.adaptivity.xmlutil.dom.Node as Node1
import nl.adaptivity.xmlutil.dom2.Attr as Attr2
import nl.adaptivity.xmlutil.dom2.Element as Element2
import nl.adaptivity.xmlutil.dom2.NamedNodeMap as NamedNodeMap2
import nl.adaptivity.xmlutil.dom2.Node as Node2


internal fun Document.createElement(name: QName): Element1 {
    return createElementNS(name.getNamespaceURI(), name.toCName())
}

internal val Node1.isElement: Boolean get() = nodeType == NodeConsts.ELEMENT_NODE

internal val Node1.isText: Boolean
    get() = when (nodeType) {
        NodeConsts.ELEMENT_NODE, NodeConsts.CDATA_SECTION_NODE -> true
        else -> false
    }

/** Implement forEach with a built-in cast to attr. */
internal inline fun NamedNodeMap1.forEachAttr(body: (Attr1) -> Unit) {
    val l = this.getLength()
    for (idx in 0 until l) {
        body(item(idx) as Attr1)
    }
}

/** Implement forEach with a built-in cast to attr. */
internal inline fun NamedNodeMap2.forEachAttr(body: (Attr2) -> Unit) {
    val l = this.getLength()
    for (idx in 0 until l) {
        body(item(idx) as Attr2)
    }
}

/** Remove all the child nodes that are elements. */
internal fun Node1.removeElementChildren() {
    val top = this
    var cur = top.firstChild
    while (cur != null) {
        val n = cur.nextSibling
        if (cur.isElement) {
            top.removeChild(cur)
        }
        cur = n
    }
}

/** A filter function on a [NamedNodeMap1] that returns a list of all
 * (attributes)[Attr1] that meet the [predicate].
 */
internal inline fun NamedNodeMap1.filterTyped(predicate: (Attr1) -> Boolean): List<Attr1> {
    val result = mutableListOf<Attr1>()
    forEachAttr { attr ->
        if (predicate(attr)) result.add(attr)
    }
    return result
}

/** A filter function on a [NamedNodeMap1] that returns a list of all
 * (attributes)[Attr1] that meet the [predicate].
 */
internal inline fun NamedNodeMap2.filterTyped(predicate: (Attr2) -> Boolean): List<Attr2> {
    val result = mutableListOf<Attr2>()
    forEachAttr { attr ->
        if (predicate(attr)) result.add(attr)
    }
    return result
}

internal fun Node1.myLookupPrefix(namespaceUri: String): String? {
    if (nodeType != NodeConsts.ELEMENT_NODE) return null
    return (this as Element1).myLookupPrefixImpl(namespaceUri, mutableSetOf())
}

internal fun Node2.myLookupPrefix(namespaceUri: String): String? {
    return (this as? Element2)?.myLookupPrefixImpl(namespaceUri, mutableSetOf())
}

private fun Element1.myLookupPrefixImpl(namespaceUri: String, seenPrefixes: MutableSet<String>): String? {
    this.getAttributes().forEachAttr { attr ->
        when {
            attr.getPrefix() == XMLConstants.XMLNS_ATTRIBUTE ->
                if (attr.getValue() == namespaceUri && attr.getLocalName() !in seenPrefixes) {
                    return attr.getLocalName()
                } else {
                    seenPrefixes.add(attr.getLocalName() ?: attr.getName())
                }

            attr.getPrefix().isNullOrBlank() && attr.getLocalName() == XMLConstants.XMLNS_ATTRIBUTE ->
                if (attr.getValue() == namespaceUri && attr.getLocalName() !in seenPrefixes) {
                    return ""
                } else {
                    seenPrefixes.add("")
                }
        }
    }
    return when (parentNode?.nodeType) {
        NodeConsts.ELEMENT_NODE -> (parentNode as Element1).myLookupPrefixImpl(namespaceUri, seenPrefixes)

        else -> null
    }
}

private fun Element2.myLookupPrefixImpl(namespaceUri: String, seenPrefixes: MutableSet<String>): String? {
    getAttributes().forEachAttr { attr ->
        when {
            attr.getPrefix() == XMLConstants.XMLNS_ATTRIBUTE ->
                if (attr.getValue() == namespaceUri && attr.getLocalName() !in seenPrefixes) {
                    return attr.getLocalName()
                } else {
                    seenPrefixes.add(attr.getLocalName() ?: attr.getName())
                }

            attr.getPrefix().isNullOrBlank() && attr.getLocalName() == XMLConstants.XMLNS_ATTRIBUTE ->
                if (attr.getValue() == namespaceUri && attr.getLocalName() !in seenPrefixes) {
                    return ""
                } else {
                    seenPrefixes.add("")
                }
        }
    }
    return (getParentNode() as? Element2)?.myLookupPrefixImpl(namespaceUri, seenPrefixes)
}

internal fun Node1.myLookupNamespaceURI(prefix: String): String? = when {
    nodeType != NodeConsts.ELEMENT_NODE -> null
    else -> {
        (this as Element1).attributes.filterTyped {
            (prefix == "" && it.getLocalName() == "xmlns") ||
                    (it.getPrefix() == "xmlns" && it.getLocalName() == prefix)
        }.firstOrNull()?.getValue() ?: parentNode?.myLookupNamespaceURI(prefix)
    }
}

internal fun Node2.myLookupNamespaceURI(prefix: String): String? = when (this) {
    is Element2 -> {
        getAttributes().filterTyped {
            (prefix == "" && it.getLocalName() == "xmlns") ||
                    (it.getPrefix() == "xmlns" && it.getLocalName() == prefix)
        }.firstOrNull()?.getValue() ?: getParentNode()?.myLookupNamespaceURI(prefix)

    }
    else -> null
}
