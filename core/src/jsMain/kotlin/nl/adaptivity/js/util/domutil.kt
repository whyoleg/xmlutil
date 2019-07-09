/*
 * Copyright (c) 2017.
 *
 * This file is part of XmlUtil.
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

package nl.adaptivity.js.util

import nl.adaptivity.xmlutil.Namespace
import nl.adaptivity.xmlutil.NamespaceContext
import nl.adaptivity.xmlutil.SimpleNamespaceContext
import nl.adaptivity.xmlutil.XmlEvent
import org.w3c.dom.*
import kotlin.dom.isElement
import kotlin.dom.isText

fun Node.asElement(): Element? = if (isElement) this as Element else null
fun Node.asText(): Text? = if (isText) this as Text else null

fun Node.removeElementChildren() {
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

inline fun NamedNodeMap.forEach(body: (Attr) -> Unit) {
    for (i in 0 until length) {
        body(item(i)!!)
    }
}

inline fun NamedNodeMap.filter(predicate: (Attr) -> Boolean): List<Attr> {
    val result = mutableListOf<Attr>()
    forEach { attr ->
        if (predicate(attr)) result.add(attr)
    }
    return result
}

inline fun <R> NamedNodeMap.map(body: (Attr) -> R): List<R> {
    val result = mutableListOf<R>()
    forEach { attr ->
        result.add(body(attr))
    }
    return result
}

inline fun NamedNodeMap.count(predicate: (Attr) -> Boolean): Int {
    var count = 0
    forEach { attr ->
        if (predicate(attr)) count++
    }
    return count
}


internal fun Node.myLookupPrefix(namespaceUri: String): String? = when {
    this !is Element -> null
    else             -> attributes.filter {
        (it.prefix == "xmlns" ||
                (it.prefix == "" && it.localName == "xmlns")) &&
                it.value == namespaceUri
    }.firstOrNull()?.let { if (it.prefix == "xmlns") it.localName else "" } ?: parentNode?.myLookupPrefix(namespaceUri)
}

internal fun Node.myLookupNamespaceURI(prefix: String): String? = when {
    this !is Element -> null
    else             -> attributes.filter {
        (prefix == "" && it.localName == "xmlns") ||
                (it.prefix == "xmlns" && it.localName == prefix)
    }.firstOrNull()?.value ?: parentNode?.myLookupNamespaceURI(prefix)
}

/** Remove namespaces attributes from a tree that have already been declared by a parent. */
internal fun Node.removeUnneededNamespaces(knownNamespaces: ExtendingNamespaceContext = ExtendingNamespaceContext()) {
    if (nodeType == Node.ELEMENT_NODE) {
        @Suppress("UnsafeCastFromDynamic")
        val elem: Element = asDynamic()

        val toRemove = mutableListOf<Attr>()

        elem.attributes.forEach { attr ->
            if (attr.prefix == "xmlns") {
                val knownUri = knownNamespaces.parent.getNamespaceURI(attr.localName)
                if (attr.value == knownUri) {
                    toRemove.add(attr)
                } else {
                    knownNamespaces.addNamespace(attr.localName, attr.value)
                }
            } else if (attr.prefix == "" && attr.localName == "xmlns") {
                val knownUri = knownNamespaces.parent.getNamespaceURI("")
                if (attr.value == knownUri) {
                    toRemove.add(attr)
                } else {
                    knownNamespaces.addNamespace("", attr.value)
                }
            }
        }
        for (attr in toRemove) {
            elem.removeAttributeNode(attr)
        }
        for (child in elem.childNodes.asList()) {
            child.removeUnneededNamespaces(knownNamespaces.extend())
        }
    }
}

internal class ExtendingNamespaceContext(val parent: NamespaceContext = SimpleNamespaceContext("", "")) :
    NamespaceContext {

    private val localNamespaces = mutableListOf<Namespace>()

    override fun getNamespaceURI(prefix: String): String? {
        return localNamespaces.firstOrNull { it.prefix == prefix }?.namespaceURI ?: parent.getNamespaceURI(prefix)
    }

    override fun getPrefix(namespaceURI: String): String? {
        return localNamespaces.firstOrNull { it.namespaceURI == namespaceURI }?.prefix ?: parent.getPrefix(namespaceURI)
    }

    override fun getPrefixes(namespaceURI: String): Iterator<String?> {
        TODO("not implemented")
    }

    fun addNamespace(prefix: String, namespaceURI: String) {
        localNamespaces.add(XmlEvent.NamespaceImpl(prefix, namespaceURI))
    }

    fun extend() = ExtendingNamespaceContext(this)
}