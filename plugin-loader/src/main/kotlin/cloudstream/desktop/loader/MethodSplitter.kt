package cloudstream.desktop.loader

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.SimpleVerifier
import java.util.zip.ZipFile

/**
 * Class hierarchy for frame merging, read from the converted plugin jar itself (class headers only, nothing is
 * loaded) with the converter's own classpath (JDK, library, android-shim) as fallback. Unknown classes are treated
 * as direct subclasses of Object, which is what the JVM's inference verifier assumes for interfaces anyway.
 */
class Hierarchy(private val jar: java.io.File?, private val nodes: Collection<ClassNode> = emptyList()) {
    private data class Info(val superName: String?, val isInterface: Boolean, val interfaces: List<String>)
    private val infos: Map<String, Info> by lazy {
        val m = HashMap<String, Info>()
        for (c in nodes) m[c.name] = Info(c.superName, c.access and ACC_INTERFACE != 0, c.interfaces.orEmpty())
        jar?.let { f ->
            ZipFile(f).use { z ->
                for (e in z.entries()) if (e.name.endsWith(".class")) runCatching {
                    val r = ClassReader(z.getInputStream(e).use { it.readBytes() })
                    m[r.className] = Info(r.superName, r.access and ACC_INTERFACE != 0, r.interfaces.toList())
                }
            }
        }
        m
    }
    private val cache = HashMap<String, Info?>()

    private fun info(name: String): Info? = infos[name] ?: cache.getOrPut(name) {
        runCatching {
            val c = Class.forName(name.replace('/', '.'), false, Hierarchy::class.java.classLoader)
            Info(c.superclass?.name?.replace('.', '/'), c.isInterface, c.interfaces.map { it.name.replace('.', '/') })
        }.getOrNull()
    }

    fun isInterface(name: String) = info(name)?.isInterface ?: false
    fun superOf(name: String): String? = if (name == "java/lang/Object") null else info(name)?.superName ?: "java/lang/Object"

    /** [sub] is [sup] or inherits from it (classes and interfaces). */
    fun isSubclass(sub: String, sup: String): Boolean {
        if (sub == sup || sup == "java/lang/Object") return true
        val seen = HashSet<String>(); val todo = ArrayDeque(listOf(sub))
        while (todo.isNotEmpty()) {
            val n = todo.removeFirst()
            if (n == sup) return true
            if (!seen.add(n)) continue
            val i = info(n) ?: continue
            i.superName?.let { todo += it }; todo += i.interfaces
        }
        return false
    }
}

/**
 * Splits a method whose bytecode exceeds the JVM's 65535-byte limit (dex2jar output of large Kotlin suspend
 * functions, e.g. a big aggregator's link resolver) by outlining single-entry regions into private static helpers.
 *
 * Region rules (all checked on the original control-flow graph):
 *  - one entry: no jump, switch or exception edge from outside lands inside it, except at its first instruction;
 *  - the operand stack is empty at the entry and at every exit target;
 *  - every try/catch block is either entirely outside, or has its range AND handler inside (moved along);
 *  - no monitorenter/monitorexit.
 * Calling convention: locals travel in an Object[] (index = slot + 1, boxed; index 0 = exit id). The helper returns
 * either a real return value, or the array itself meaning "left the region through exit #a[0]"; the caller then
 * reloads the locals the region wrote and jumps to that exit's original target.
 *
 * Classes are version 50 without StackMapTables (dex2jar), so the JVM verifies them by type inference and no frames
 * have to be written.
 */
class MethodSplitter(
    private val hierarchy: Hierarchy,
    /** Split until the method is at most this many bytes (tests force-split normal methods with a lower target). */
    private val target: Int = TARGET,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val LIMIT = 65535
        /** Keep a margin: the size estimate is conservative but not exact. */
        const val TARGET = 60000
        const val MAX_REGION = 50000
        const val MIN_REGION = 1500
    }

    /** Splits [m] (in [cn]) until it fits; returns the helpers added to [cn], or throws if impossible. */
    fun split(cn: ClassNode, m: MethodNode, sizer: (MethodNode) -> Long = { estimate(it.instructions.toArray().toList()).toLong() }): Int {
        val ret = Type.getReturnType(m.desc)
        require(ret.sort == Type.OBJECT || ret.sort == Type.ARRAY) { "only methods returning a reference are split (${m.desc})" }
        var helpers = 0
        while (true) {
            val actual = sizer(m)
            if (actual <= target) break
            check(helpers < 40) { "too many split rounds" }
            // Region sizes are estimated (conservatively); scale the real excess into estimate units.
            val est = estimate(m.instructions.toArray().toList()).toLong()
            val need = ((actual - target) * est / actual).toInt()
            outlineOnce(cn, m, "${m.name}\$split\$$helpers", need)
            helpers++
            log("split ${cn.name}.${m.name}: $actual -> ${sizer(m)} bytes (helper #$helpers)")
        }
        return helpers
    }

    // ------------------------------------------------------------------------------------------------ analysis

    private inner class LenientVerifier(cn: ClassNode) : SimpleVerifier(
        ASM9, Type.getObjectType(cn.name), cn.superName?.let { Type.getObjectType(it) },
        cn.interfaces.map { Type.getObjectType(it) }, cn.access and ACC_INTERFACE != 0,
    ) {
        // Never reject: the code already passed dex2jar; we only want the types.
        override fun isSubTypeOf(value: BasicValue, expected: BasicValue) = true
        override fun isInterface(type: Type) = type.sort == Type.OBJECT && hierarchy.isInterface(type.internalName)
        override fun getSuperClass(type: Type): Type? =
            if (type.sort != Type.OBJECT) Type.getObjectType("java/lang/Object") else hierarchy.superOf(type.internalName)?.let { Type.getObjectType(it) }
        override fun isAssignableFrom(type: Type, other: Type): Boolean {
            if (type == other) return true
            if (type.sort == Type.OBJECT && type.internalName == "java/lang/Object") return true
            if (type.sort == Type.ARRAY && other.sort == Type.ARRAY) {
                val a = type.elementType; val b = other.elementType
                return type.dimensions == other.dimensions && (a == b || (a.sort == Type.OBJECT && b.sort == Type.OBJECT && isAssignableFrom(a, b)))
            }
            if (type.sort == Type.OBJECT && other.sort == Type.OBJECT) return hierarchy.isSubclass(other.internalName, type.internalName)
            return false
        }
        override fun getClass(type: Type): Class<*> = Any::class.java // never used with the overrides above
        // dex2jar sometimes loads array elements from values merged to Object: accept and type the element as Object.
        override fun isArrayValue(value: BasicValue) = true
        override fun getElementValue(objectArrayValue: BasicValue): BasicValue {
            val t = objectArrayValue.type
            return if (t != null && t.sort == Type.ARRAY) super.getElementValue(objectArrayValue) else newValue(Type.getObjectType("java/lang/Object"))
        }
    }

    private fun frames(cn: ClassNode, m: MethodNode): Array<Frame<BasicValue>?> {
        // dex2jar's tree may not carry real maxs (its writer computes them): derive locals, grow the stack bound on demand.
        var locals = (Type.getArgumentsAndReturnSizes(m.desc) shr 2) - (if (m.access and ACC_STATIC != 0) 1 else 0)
        for (x in m.instructions) when (x) {
            is VarInsnNode -> locals = maxOf(locals, x.`var` + if (x.opcode == LLOAD || x.opcode == DLOAD || x.opcode == LSTORE || x.opcode == DSTORE) 2 else 1)
            is IincInsnNode -> locals = maxOf(locals, x.`var` + 1)
        }
        m.maxLocals = maxOf(m.maxLocals, locals)
        if (m.maxStack < 16) m.maxStack = 16
        while (true) {
            try { return Analyzer(LenientVerifier(cn)).analyze(cn.name, m) }
            catch (e: org.objectweb.asm.tree.analysis.AnalyzerException) {
                if (e.message?.contains("Insufficient maximum stack size") == true && m.maxStack < 4096) m.maxStack *= 2 else throw e
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ sizes

    private fun size(n: AbstractInsnNode): Int = when (n) {
        is LabelNode, is LineNumberNode, is FrameNode -> 0
        is VarInsnNode -> if (n.`var` <= 3 && n.opcode != RET) 1 else if (n.`var` < 256) 2 else 4
        is IincInsnNode -> if (n.`var` < 256 && n.incr in -128..127) 3 else 6
        is IntInsnNode -> if (n.opcode == SIPUSH) 3 else 2
        is LdcInsnNode -> 3
        is JumpInsnNode -> 3 // the writer widens the rare far jumps; sizes are re-measured after every round
        is TableSwitchInsnNode -> 16 + 4 * n.labels.size
        is LookupSwitchInsnNode -> 12 + 8 * n.labels.size
        is MethodInsnNode -> if (n.opcode == INVOKEINTERFACE) 5 else 3
        is InvokeDynamicInsnNode -> 5
        is MultiANewArrayInsnNode -> 4
        is TypeInsnNode, is FieldInsnNode -> 3
        else -> 1
    }

    private fun estimate(insns: List<AbstractInsnNode>) = insns.sumOf { size(it) }

    // ------------------------------------------------------------------------------------------------ region search

    private data class Region(val s: Int, val e: Int, val bytes: Int, val cost: Int)
    private var lastBreakdown = ""
    /** Instructions this splitter inserted as call sites (identity set). */
    private val inserted: MutableSet<AbstractInsnNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    /** Use/def slots of one instruction (long/double cover two slots). */
    private fun useDef(x: AbstractInsnNode, use: java.util.BitSet, def: java.util.BitSet) {
        when (x) {
            is VarInsnNode -> {
                val wide = x.opcode == LLOAD || x.opcode == DLOAD || x.opcode == LSTORE || x.opcode == DSTORE
                val target = if (x.opcode in ILOAD..ALOAD) use else def
                target.set(x.`var`); if (wide) target.set(x.`var` + 1)
            }
            is IincInsnNode -> { use.set(x.`var`); def.set(x.`var`) }
        }
    }

    /** Backward liveness: live-in set per instruction (exception edges included). */
    private fun liveness(insns: Array<AbstractInsnNode>, succ: Array<IntArray?>, handlersOf: Array<IntArray>, falls: (Int) -> Boolean): Array<java.util.BitSet> {
        val n = insns.size
        val use = Array(n) { java.util.BitSet() }; val def = Array(n) { java.util.BitSet() }
        for (i in 0 until n) useDef(insns[i], use[i], def[i])
        val liveIn = Array(n) { java.util.BitSet() }
        val preds = Array(n) { ArrayList<Int>(2) }
        for (i in 0 until n) {
            if (falls(i) && i + 1 < n) preds[i + 1] += i
            for (t in succ[i]!!) preds[t] += i
            for (h in handlersOf[i]) preds[h] += i
        }
        val work = java.util.ArrayDeque<Int>(); val queued = BooleanArray(n) { true }
        for (i in n - 1 downTo 0) work.add(i)
        val out = java.util.BitSet()
        while (work.isNotEmpty()) {
            val i = work.poll(); queued[i] = false
            out.clear()
            if (falls(i) && i + 1 < n) out.or(liveIn[i + 1])
            for (t in succ[i]!!) out.or(liveIn[t])
            for (h in handlersOf[i]) out.or(liveIn[h])
            out.andNot(def[i]); out.or(use[i])
            if (out != liveIn[i]) {
                liveIn[i] = out.clone() as java.util.BitSet
                for (p in preds[i]) if (!queued[p]) { queued[p] = true; work.add(p) }
            }
        }
        return liveIn
    }

    private fun outlineOnce(cn: ClassNode, m: MethodNode, helperName: String, need: Int) {
        val insns = m.instructions.toArray()
        val n = insns.size
        val idx = HashMap<AbstractInsnNode, Int>(n * 2).also { map -> insns.forEachIndexed { i, x -> map[x] = i } }
        val fr = frames(cn, m)

        // Incoming non-fallthrough edges: min/max source index per target.
        val minSrc = IntArray(n) { Int.MAX_VALUE }; val maxSrc = IntArray(n) { -1 }
        fun edge(from: Int, to: Int) { if (from < minSrc[to]) minSrc[to] = from; if (from > maxSrc[to]) maxSrc[to] = from }
        val succ = arrayOfNulls<IntArray>(n)
        for (i in 0 until n) {
            val x = insns[i]
            val t: List<Int> = when (x) {
                is JumpInsnNode -> listOf(idx[x.label]!!)
                is TableSwitchInsnNode -> (x.labels + x.dflt).map { idx[it]!! }
                is LookupSwitchInsnNode -> (x.labels + x.dflt).map { idx[it]!! }
                else -> emptyList()
            }
            t.forEach { edge(i, it) }
            succ[i] = t.toIntArray()
        }
        val tcbs = m.tryCatchBlocks.map { Triple(idx[it.start]!!, idx[it.end]!!, idx[it.handler]!!) }
        tcbs.forEach { (a, b, h) -> if (b > a) { edge(a, h); edge(b - 1, h) } }
        // Call sites inserted by earlier rounds are never outlined again (that would only nest them).
        val blocked = BooleanArray(n) { insns[it].opcode == MONITORENTER || insns[it].opcode == MONITOREXIT || insns[it].opcode == JSR || insns[it].opcode == RET || insns[it] in inserted }
        val bytes = IntArray(n + 1).also { p -> for (i in 0 until n) p[i + 1] = p[i] + size(insns[i]) }
        fun falls(i: Int): Boolean {
            val o = insns[i].opcode
            return !(o == GOTO || o == ATHROW || o in IRETURN..RETURN || insns[i] is TableSwitchInsnNode || insns[i] is LookupSwitchInsnNode)
        }
        fun stackEmpty(i: Int) = i < n && fr[i] != null && fr[i]!!.stackSize == 0

        /** Entry points of [s, e): its start plus every instruction jumped/switched to from outside. */
        fun entriesOf(s: Int, e: Int): List<Int> {
            val out = arrayListOf(s)
            for (j in s + 1 until e) if (minSrc[j] < s || (maxSrc[j] >= e && maxSrc[j] != -1)) out += j
            return out
        }

        fun valid(s: Int, e: Int): Boolean {
            for ((a, b, h) in tcbs) {
                val overlaps = a < e && b > s
                if (overlaps && !(a >= s && b <= e && h >= s && h < e)) return false
                if (!overlaps && h > s && h < e) return false
            }
            for (i in s until e) {
                if (fr[i] == null) continue
                for (t in succ[i]!!) if ((t < s || t >= e) && !stackEmpty(t)) return false
            }
            if (e - 1 >= s && falls(e - 1) && fr[e - 1] != null && !stackEmpty(e)) return false
            // Every way in (beyond the start) must be a label with an empty stack.
            for (j in entriesOf(s, e)) if (j != s && (!stackEmpty(j) || insns[j] !is LabelNode)) return false
            return true
        }

        // Liveness, so only values actually needed cross the call boundary.
        val handlersOf = Array(n) { IntArray(0) }
        run {
            val lists = Array(n) { ArrayList<Int>(0) }
            for ((a, b, h) in tcbs) for (i in a until b) lists[i] += h
            for (i in 0 until n) handlersOf[i] = lists[i].toIntArray()
        }
        val liveIn = liveness(insns, succ, handlersOf, ::falls)

        /** Bytes the call site adds in the caller (array, inputs, dispatch, per-exit reloads). */
        fun overhead(s: Int, e: Int): Int {
            val touched = java.util.BitSet()
            for (i in s until e) useDef(insns[i], touched, touched)
            val written = java.util.BitSet(); for (i in s until e) useDef(insns[i], java.util.BitSet(), written)
            val entries = entriesOf(s, e)
            val inputs = entries.sumOf { j -> (liveIn[j].clone() as java.util.BitSet).apply { and(touched) }.cardinality() }
            val exits = HashSet<Int>()
            for (i in s until e) for (t in succ[i]!!) if (t < s || t >= e) exits += t
            if (falls(e - 1)) exits += e
            // Outputs are reloaded once (union over exits), see outline().
            val union = java.util.BitSet()
            for (t in exits) if (t < n) union.or((liveIn[t].clone() as java.util.BitSet).apply { and(written) })
            val outs = union.cardinality()
            lastBreakdown = "entries=${entries.size} inputs=$inputs exits=${exits.size} outs=$outs"
            return 24 + 14 * entries.size + 9 * inputs + 16 + 4 * exits.size + 11 * outs + 3 * exits.size
        }

        val upper = minOf(MAX_REGION, bytes[n] - 4000)
        // Several medium regions work better than one huge one (Kotlin state machines have many entry labels).
        val want = maxOf(minOf(need + 200, 8000), minOf(MIN_REGION, need + 200))
        var best: Region? = null
        var maxValid = 0; var maxNet = Int.MIN_VALUE; var maxValidInfo = ""; var maxNetInfo = ""
        search@ for (s in 0 until n) {
            if (best != null) break@search // first region that saves enough is taken (rounds repeat anyway)
            if (!stackEmpty(s) || blocked[s] || (s > 0 && insns[s] !is LabelNode)) continue
            var e = s + 1
            var lastChecked = 0
            while (e <= n) {
                val j = e - 1
                if (blocked[j]) break
                val sz = bytes[e] - bytes[s]
                if (sz > upper) break
                // Regions may have several entries now; only check every ~300 bytes of growth (validity is O(region)).
                if (sz >= minOf(2000, want) && sz - lastChecked >= minOf(600, want / 4 + 1)) lastChecked = sz else { e++; continue }
                if (valid(s, e)) {
                    val net = sz - overhead(s, e)
                    if (sz > maxValid) { maxValid = sz; maxValidInfo = "[$s,$e) $lastBreakdown net=$net" }
                    if (net > maxNet) { maxNet = net; maxNetInfo = "[$s,$e) size=$sz $lastBreakdown" }
                    if (sz < want) { e++; continue }
                    if (net >= want) {
                        // Prefer the cheapest call site, then the smallest region.
                        val cost = (sz - net) * 4 + sz / 100
                        if (best == null || cost < best.cost) best = Region(s, e, sz, cost)
                        break
                    }
                }
                e++
            }
        }
        val r = best ?: error("no outlinable region saving >= $want bytes in ${cn.name}.${m.name} (largest valid region ~$maxValid $maxValidInfo; best net saving ~$maxNet $maxNetInfo; method ~${bytes[n]}, ${n} insns, ${m.maxLocals} locals)")
        overhead(r.s, r.e)
        log("region [${r.s}, ${r.e}) ~${r.bytes} bytes, call-site cost ~${(r.cost - r.bytes / 100) / 4} ($lastBreakdown)")
        outline(cn, m, helperName, insns, idx, fr, r.s, r.e, succ, liveIn, entriesOf(r.s, r.e))
    }

    // ------------------------------------------------------------------------------------------------ rewrite

    private enum class Kind { INT, FLOAT, LONG, DOUBLE, REF }

    private fun kindOf(v: BasicValue?): Kind? {
        val t = v?.type ?: return null // uninitialized / TOP
        return when (t.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Kind.INT
            Type.FLOAT -> Kind.FLOAT; Type.LONG -> Kind.LONG; Type.DOUBLE -> Kind.DOUBLE
            Type.OBJECT, Type.ARRAY -> Kind.REF
            else -> null
        }
    }

    private fun pushInt(il: InsnList, v: Int) = il.add(when {
        v in -1..5 -> InsnNode(ICONST_0 + v)
        v in -128..127 -> IntInsnNode(BIPUSH, v)
        v in -32768..32767 -> IntInsnNode(SIPUSH, v)
        else -> LdcInsnNode(v)
    })

    /** value on stack -> boxed Object */
    private fun box(il: InsnList, k: Kind) {
        when (k) {
            Kind.INT -> il.add(MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false))
            Kind.FLOAT -> il.add(MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false))
            Kind.LONG -> il.add(MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false))
            Kind.DOUBLE -> il.add(MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false))
            Kind.REF -> {}
        }
    }

    /** Object on stack -> typed value (unboxed or cast to [t]) */
    private fun unbox(il: InsnList, k: Kind, t: Type) {
        when (k) {
            Kind.INT -> { il.add(TypeInsnNode(CHECKCAST, "java/lang/Integer")); il.add(MethodInsnNode(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)) }
            Kind.FLOAT -> { il.add(TypeInsnNode(CHECKCAST, "java/lang/Float")); il.add(MethodInsnNode(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false)) }
            Kind.LONG -> { il.add(TypeInsnNode(CHECKCAST, "java/lang/Long")); il.add(MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false)) }
            Kind.DOUBLE -> { il.add(TypeInsnNode(CHECKCAST, "java/lang/Double")); il.add(MethodInsnNode(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)) }
            Kind.REF -> {
                val name = if (t.sort == Type.ARRAY) t.descriptor else t.internalName
                if (name != "java/lang/Object" && name != "null" && !name.startsWith("Lnull")) il.add(TypeInsnNode(CHECKCAST, name))
            }
        }
    }

    /** A boxed zero / null of kind [k] (for slots that are dead at a particular exit). */
    private fun defaultBoxed(il: InsnList, k: Kind) {
        when (k) {
            Kind.INT -> il.add(InsnNode(ICONST_0)); Kind.FLOAT -> il.add(InsnNode(FCONST_0))
            Kind.LONG -> il.add(InsnNode(LCONST_0)); Kind.DOUBLE -> il.add(InsnNode(DCONST_0))
            Kind.REF -> { il.add(InsnNode(ACONST_NULL)); return }
        }
        box(il, k)
    }

    private fun load(k: Kind) = when (k) { Kind.INT -> ILOAD; Kind.FLOAT -> FLOAD; Kind.LONG -> LLOAD; Kind.DOUBLE -> DLOAD; Kind.REF -> ALOAD }
    private fun store(k: Kind) = when (k) { Kind.INT -> ISTORE; Kind.FLOAT -> FSTORE; Kind.LONG -> LSTORE; Kind.DOUBLE -> DSTORE; Kind.REF -> ASTORE }

    private data class Slot(val slot: Int, val kind: Kind, val type: Type)

    /** Slots with a usable value in [f] restricted to [among]. */
    private fun slots(f: Frame<BasicValue>, among: Set<Int>): List<Slot> = among.sorted().mapNotNull { i ->
        if (i >= f.locals) return@mapNotNull null
        val v = f.getLocal(i); val k = kindOf(v) ?: return@mapNotNull null
        Slot(i, k, v.type)
    }

    private fun outline(
        cn: ClassNode, m: MethodNode, helperName: String, insns: Array<AbstractInsnNode>, idx: Map<AbstractInsnNode, Int>,
        fr: Array<Frame<BasicValue>?>, s: Int, e: Int, succ: Array<IntArray?>, liveIn: Array<java.util.BitSet>, entries: List<Int>,
    ) {
        val n = insns.size
        val maxL = m.maxLocals
        val region = (s until e).map { insns[it] }
        val read = HashSet<Int>(); val written = HashSet<Int>()
        for (x in region) when (x) {
            is VarInsnNode -> if (x.opcode in ILOAD..ALOAD) read += x.`var` else { written += x.`var`; if (x.opcode == LSTORE || x.opcode == DSTORE) written += x.`var` + 1 }
            is IincInsnNode -> { read += x.`var`; written += x.`var` }
        }
        fun live(i: Int, among: Set<Int>) = if (i >= n) emptySet() else among.filter { liveIn[i][it] }.toSet()
        // Inputs per entry: live there and touched by the region (a slot written on only some paths must still arrive intact).
        val inputsOf = entries.associateWith { j -> slots(fr[j]!!, live(j, read + written)) }

        // Exits: (target index) in first-seen order; e (fallthrough) included when the last instruction falls through.
        val exits = LinkedHashSet<Int>()
        for (i in s until e) for (t in succ[i]!!) if (t < s || t >= e) exits += t
        val lastFalls = insns[e - 1].opcode.let { o -> !(o == GOTO || o == ATHROW || o in IRETURN..RETURN) } &&
            insns[e - 1] !is TableSwitchInsnNode && insns[e - 1] !is LookupSwitchInsnNode && fr[e - 1] != null
        if (lastFalls) exits += e
        val exitList = exits.toList()
        // Outputs per exit: written by the region and still used after the exit target.
        val outs = exitList.associateWith { t -> if (t >= n) emptyList() else slots(fr[t]!!, live(t, written)) }
        // Slots with one kind/type at every exit that needs them are reloaded ONCE before the dispatch (the helper
        // stores a harmless default at exits where the slot is dead); the rest are reloaded per exit.
        val shared: List<Slot> = outs.values.flatten().groupBy { it.slot }
            .filterValues { l -> l.all { it.kind == l[0].kind && it.type == l[0].type } }.map { it.value[0] }.sortedBy { it.slot }
        val sharedSlots = shared.map { it.slot }.toSet()
        val own = outs.mapValues { (_, l) -> l.filter { it.slot !in sharedSlots } }

        // ---- helper method
        val h = MethodNode(ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC, helperName, "([Ljava/lang/Object;)Ljava/lang/Object;", null, null)
        val arr = maxL // helper slot holding the array
        val hl = h.instructions
        val labelMap = HashMap<LabelNode, LabelNode>()
        for (x in region) if (x is LabelNode) labelMap[x] = LabelNode()
        val startLabel = LabelNode() // helper position of the region start (entry 0)
        hl.add(VarInsnNode(ALOAD, 0)); hl.add(VarInsnNode(ASTORE, arr))
        // Entry dispatch: a[0] = entry number; each entry loads its own inputs and jumps to its spot in the code.
        val entryBlock = entries.associateWith { LabelNode() }
        if (entries.size > 1) {
            hl.add(VarInsnNode(ALOAD, arr)); hl.add(InsnNode(ICONST_0)); hl.add(InsnNode(AALOAD)); unbox(hl, Kind.INT, Type.INT_TYPE)
            hl.add(TableSwitchInsnNode(0, entries.size - 1, entryBlock[s]!!, *entries.map { entryBlock[it]!! }.toTypedArray()))
        }
        for (j in entries) {
            hl.add(entryBlock[j]!!)
            for (sl in inputsOf[j]!!) { hl.add(VarInsnNode(ALOAD, arr)); pushInt(hl, sl.slot + 1); hl.add(InsnNode(AALOAD)); unbox(hl, sl.kind, sl.type); hl.add(VarInsnNode(store(sl.kind), sl.slot)) }
            hl.add(JumpInsnNode(GOTO, if (j == s) startLabel else labelMap[insns[j] as LabelNode]!!))
        }
        hl.add(startLabel)
        val exitLabel = exitList.associateWith { LabelNode() }
        val cloneMap = object : java.util.AbstractMap<LabelNode, LabelNode>() {
            override val entries get() = throw UnsupportedOperationException()
            override fun get(key: LabelNode): LabelNode = labelMap[key] ?: idx[key]?.let { exitLabel[it] }
                ?: error("label outside the region that is not an exit target")
        }
        for (x in region) if (x !is FrameNode && x !is LineNumberNode) hl.add(x.clone(cloneMap))
        val regionEnd = LabelNode().also { hl.add(it) } // end of moved try ranges that stop exactly at the region end
        if (lastFalls) hl.add(JumpInsnNode(GOTO, exitLabel[e]!!))
        exitList.forEachIndexed { k, t ->
            hl.add(exitLabel[t]!!)
            val liveHere = outs[t]!!.map { it.slot }.toSet()
            for (sl in shared) {
                hl.add(VarInsnNode(ALOAD, arr)); pushInt(hl, sl.slot + 1)
                if (sl.slot in liveHere) { hl.add(VarInsnNode(load(sl.kind), sl.slot)); box(hl, sl.kind) } else defaultBoxed(hl, sl.kind)
                hl.add(InsnNode(AASTORE))
            }
            for (sl in own[t]!!) { hl.add(VarInsnNode(ALOAD, arr)); pushInt(hl, sl.slot + 1); hl.add(VarInsnNode(load(sl.kind), sl.slot)); box(hl, sl.kind); hl.add(InsnNode(AASTORE)) }
            hl.add(VarInsnNode(ALOAD, arr)); hl.add(InsnNode(ICONST_0)); pushInt(hl, k); box(hl, Kind.INT); hl.add(InsnNode(AASTORE))
            hl.add(VarInsnNode(ALOAD, arr)); hl.add(InsnNode(ARETURN))
        }
        // try/catch blocks entirely inside the region move with it.
        val moved = m.tryCatchBlocks.filter { idx[it.start]!! >= s && idx[it.end]!! <= e }
        for (tb in moved) h.tryCatchBlocks.add(TryCatchBlockNode(labelMap[tb.start]!!, labelMap[tb.end] ?: regionEnd, labelMap[tb.handler]!!, tb.type))
        h.maxLocals = maxL + 1; h.maxStack = 0
        cn.methods.add(h)

        // ---- call site in the original method
        // Target labels in the caller (fallthrough target e may need a fresh label); placed before anything is removed.
        val targetLabel = HashMap<Int, LabelNode>()
        for (t in exitList) targetLabel[t] = (if (t < n) insns[t] as? LabelNode else null) ?: LabelNode().also { lbl ->
            if (t < n) m.instructions.insertBefore(insns[t], lbl) else m.instructions.add(lbl)
        }
        // Remove the region. Leading labels at s stay where they are; labels of the other entries are re-attached to
        // their stubs below (outside jumps keep pointing at the same LabelNode objects).
        var keep = s
        while (keep < e && insns[keep] is LabelNode) keep++
        val anchor: AbstractInsnNode? = if (keep > s) insns[keep - 1] else insns.getOrNull(s - 1)
        val entryLabels = entries.filter { it != s }.associateWith { j ->
            var k = j; val l = ArrayList<LabelNode>(); while (k < e && insns[k] is LabelNode) { l += insns[k] as LabelNode; k++ }; l
        }
        for (i in keep until e) m.instructions.remove(insns[i])

        val am = maxL // new local in the caller
        val il = InsnList()
        val lCall = LabelNode()
        entries.forEachIndexed { k, j ->
            if (j != s) entryLabels[j]!!.forEach { il.add(it) }
            pushInt(il, maxL + 1); il.add(TypeInsnNode(ANEWARRAY, "java/lang/Object")); il.add(VarInsnNode(ASTORE, am))
            for (sl in inputsOf[j]!!) { il.add(VarInsnNode(ALOAD, am)); pushInt(il, sl.slot + 1); il.add(VarInsnNode(load(sl.kind), sl.slot)); box(il, sl.kind); il.add(InsnNode(AASTORE)) }
            if (entries.size > 1) { il.add(VarInsnNode(ALOAD, am)); il.add(InsnNode(ICONST_0)); pushInt(il, k); box(il, Kind.INT); il.add(InsnNode(AASTORE)) }
            if (k < entries.size - 1) il.add(JumpInsnNode(GOTO, lCall))
        }
        il.add(lCall)
        il.add(VarInsnNode(ALOAD, am)); il.add(MethodInsnNode(INVOKESTATIC, cn.name, helperName, h.desc, cn.access and ACC_INTERFACE != 0))
        val lExits = LabelNode()
        il.add(InsnNode(DUP)); il.add(VarInsnNode(ALOAD, am)); il.add(JumpInsnNode(IF_ACMPEQ, lExits))
        val ret = Type.getReturnType(m.desc)
        if (ret.internalName != "java/lang/Object") il.add(TypeInsnNode(CHECKCAST, if (ret.sort == Type.ARRAY) ret.descriptor else ret.internalName))
        il.add(InsnNode(ARETURN))
        il.add(lExits); il.add(InsnNode(POP))
        if (exitList.isEmpty()) { il.add(InsnNode(ACONST_NULL)); il.add(InsnNode(ATHROW)) }
        else {
            for (sl in shared) { il.add(VarInsnNode(ALOAD, am)); pushInt(il, sl.slot + 1); il.add(InsnNode(AALOAD)); unbox(il, sl.kind, sl.type); il.add(VarInsnNode(store(sl.kind), sl.slot)) }
            val reload = exitList.associateWith { LabelNode() }
            if (exitList.size > 1) {
                il.add(VarInsnNode(ALOAD, am)); il.add(InsnNode(ICONST_0)); il.add(InsnNode(AALOAD)); unbox(il, Kind.INT, Type.INT_TYPE)
                il.add(TableSwitchInsnNode(0, exitList.size - 1, reload[exitList[0]]!!, *exitList.map { reload[it]!! }.toTypedArray()))
            }
            for (t in exitList) {
                il.add(reload[t]!!)
                for (sl in own[t]!!) { il.add(VarInsnNode(ALOAD, am)); pushInt(il, sl.slot + 1); il.add(InsnNode(AALOAD)); unbox(il, sl.kind, sl.type); il.add(VarInsnNode(store(sl.kind), sl.slot)) }
                il.add(JumpInsnNode(GOTO, targetLabel[t]!!))
            }
        }
        // Insert the call site where the region was.
        il.forEach { inserted += it }
        if (anchor != null) m.instructions.insert(anchor, il)
        else m.instructions.insert(il) // region started at instruction 0
        m.tryCatchBlocks.removeAll(moved)
        m.maxLocals = maxL + 1
    }
}
