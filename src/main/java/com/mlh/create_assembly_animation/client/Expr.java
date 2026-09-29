package com.mlh.create_assembly_animation.client;

import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Expr {

    private static final float TAU = (float) (Math.PI * 2.0);

    private Expr() {
    }

    @FunctionalInterface
    interface Node {

        float eval(float[] v);
    }

    record Compiled(Node node, boolean dynamic) {

        float eval(final float[] v) {
            return this.node.eval(v);
        }

        static Compiled constant(final float value) {
            return new Compiled(v -> value, false);
        }
    }

    static final class Names {

        private final Map<String, Integer> slots;
        private final BitSet dynamic;

        Names() {
            this(new LinkedHashMap<>(), new BitSet());
        }

        private Names(final Map<String, Integer> slots, final BitSet dynamic) {
            this.slots = slots;
            this.dynamic = dynamic;
        }

        int add(final String name, final boolean changing) {
            final Integer existing = this.slots.get(name);
            final int slot = existing != null ? existing : this.slots.size();
            if (existing == null)
                this.slots.put(name, slot);
            this.dynamic.set(slot, changing);
            return slot;
        }

        int slot(final String name) {
            final Integer slot = this.slots.get(name);
            return slot == null ? -1 : slot;
        }

        boolean has(final String name) {
            return this.slots.containsKey(name);
        }

        int size() {
            return this.slots.size();
        }

        Names extend() {
            return new Names(new LinkedHashMap<>(this.slots), (BitSet) this.dynamic.clone());
        }
    }

    static Compiled compile(final String source, final Names names) {
        final Parser parser = new Parser(source, names);
        final Compiled compiled = parser.ternary();
        if (parser.peek() != null)
            throw parser.error("unexpected " + parser.peek());
        return compiled;
    }

    private static int mixBits(int x) {
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        x *= 0x846CA68B;
        x ^= x >>> 16;
        return x;
    }

    static float hash(final int x, final int y, final int z) {
        final int h = mixBits(x * 0x9E3779B1 ^ mixBits(y + 0x85EBCA77) ^ mixBits(z * 0xC2B2AE3D + 0x27D4EB2F));
        return (h >>> 8) / 16777216f;
    }

    static float noise(final float px, final float py, final float pz) {
        final int cx = Mth.floor(px);
        final int cy = Mth.floor(py);
        final int cz = Mth.floor(pz);
        final float sx = hermite(px - cx);
        final float sy = hermite(py - cy);
        final float sz = hermite(pz - cz);

        final float x00 = Mth.lerp(sx, hash(cx, cy, cz), hash(cx + 1, cy, cz));
        final float x10 = Mth.lerp(sx, hash(cx, cy + 1, cz), hash(cx + 1, cy + 1, cz));
        final float x01 = Mth.lerp(sx, hash(cx, cy, cz + 1), hash(cx + 1, cy, cz + 1));
        final float x11 = Mth.lerp(sx, hash(cx, cy + 1, cz + 1), hash(cx + 1, cy + 1, cz + 1));
        return Mth.lerp(sz, Mth.lerp(sy, x00, x10), Mth.lerp(sy, x01, x11));
    }

    static float fbm(final float px, final float py, final float pz) {
        return noise(px, py, pz) * 0.65f + noise(px * 2.3f + 17.1f, py * 2.3f + 17.1f, pz * 2.3f + 17.1f) * 0.35f;
    }

    static float random(final float seed, final float n) {
        final float v = Mth.sin(seed * 12.9898f + n * 78.233f) * 43758.5453f;
        return v - Mth.floor(v);
    }

    private static float hermite(final float f) {
        return f * f * (3f - 2f * f);
    }

    private static float smooth(final float t) {
        final float c = t < 0f ? 0f : Math.min(t, 1f);
        return c * c * (3f - 2f * c);
    }

    private static final class Parser {

        private final String source;
        private final Names names;
        private final List<String> tokens = new ArrayList<>();
        private int at;

        Parser(final String source, final Names names) {
            this.source = source;
            this.names = names;
            this.tokenize();
        }

        private void tokenize() {
            final String s = this.source;
            int i = 0;
            while (i < s.length()) {
                final char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                    int j = i;
                    while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.'))
                        j++;
                    if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                        j++;
                        if (j < s.length() && (s.charAt(j) == '-' || s.charAt(j) == '+'))
                            j++;
                        while (j < s.length() && Character.isDigit(s.charAt(j)))
                            j++;
                    }
                    this.tokens.add(s.substring(i, j));
                    i = j;
                } else if (Character.isLetter(c) || c == '_') {
                    int j = i;
                    while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_'))
                        j++;
                    this.tokens.add(s.substring(i, j));
                    i = j;
                } else {
                    final String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                    if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                            || two.equals("<=") || two.equals(">=")) {
                        this.tokens.add(two);
                        i += 2;
                    } else if ("+-*/%()<>!?:,".indexOf(c) >= 0) {
                        this.tokens.add(String.valueOf(c));
                        i++;
                    } else {
                        throw new IllegalArgumentException("'" + c + "' in " + this.source);
                    }
                }
            }
        }

        String peek() {
            return this.at < this.tokens.size() ? this.tokens.get(this.at) : null;
        }

        private boolean take(final String token) {
            if (token.equals(this.peek())) {
                this.at++;
                return true;
            }
            return false;
        }

        private void expect(final String token) {
            if (!this.take(token))
                throw this.error("expected " + token);
        }

        IllegalArgumentException error(final String message) {
            return new IllegalArgumentException(message + " in " + this.source);
        }

        Compiled ternary() {
            final Compiled test = this.or();
            if (!this.take("?"))
                return test;
            final Compiled yes = this.ternary();
            this.expect(":");
            final Compiled no = this.ternary();
            final Node t = test.node();
            final Node a = yes.node();
            final Node b = no.node();
            return new Compiled(v -> t.eval(v) != 0f ? a.eval(v) : b.eval(v), any(test, yes, no));
        }

        private Compiled or() {
            Compiled left = this.and();
            while (this.take("||")) {
                final Node a = left.node();
                final Compiled right = this.and();
                final Node b = right.node();
                left = new Compiled(v -> a.eval(v) != 0f || b.eval(v) != 0f ? 1f : 0f, any(left, right));
            }
            return left;
        }

        private Compiled and() {
            Compiled left = this.compare();
            while (this.take("&&")) {
                final Node a = left.node();
                final Compiled right = this.compare();
                final Node b = right.node();
                left = new Compiled(v -> a.eval(v) != 0f && b.eval(v) != 0f ? 1f : 0f, any(left, right));
            }
            return left;
        }

        private Compiled compare() {
            Compiled left = this.sum();
            while (true) {
                final String op = this.peek();
                if (!"<".equals(op) && !">".equals(op) && !"<=".equals(op) && !">=".equals(op)
                        && !"==".equals(op) && !"!=".equals(op))
                    return left;
                this.at++;
                final Node a = left.node();
                final Compiled right = this.sum();
                final Node b = right.node();
                final Node node = switch (op) {
                    case "<" -> v -> a.eval(v) < b.eval(v) ? 1f : 0f;
                    case ">" -> v -> a.eval(v) > b.eval(v) ? 1f : 0f;
                    case "<=" -> v -> a.eval(v) <= b.eval(v) ? 1f : 0f;
                    case ">=" -> v -> a.eval(v) >= b.eval(v) ? 1f : 0f;
                    case "==" -> v -> a.eval(v) == b.eval(v) ? 1f : 0f;
                    default -> v -> a.eval(v) != b.eval(v) ? 1f : 0f;
                };
                left = new Compiled(node, any(left, right));
            }
        }

        private Compiled sum() {
            Compiled left = this.product();
            while (true) {
                final boolean plus = this.take("+");
                if (!plus && !this.take("-"))
                    return left;
                final Node a = left.node();
                final Compiled right = this.product();
                final Node b = right.node();
                left = new Compiled(plus ? v -> a.eval(v) + b.eval(v) : v -> a.eval(v) - b.eval(v), any(left, right));
            }
        }

        private Compiled product() {
            Compiled left = this.unary();
            while (true) {
                final String op = this.peek();
                if (!"*".equals(op) && !"/".equals(op) && !"%".equals(op))
                    return left;
                this.at++;
                final Node a = left.node();
                final Compiled right = this.unary();
                final Node b = right.node();
                final Node node = switch (op) {
                    case "*" -> v -> a.eval(v) * b.eval(v);
                    case "/" -> v -> a.eval(v) / b.eval(v);
                    default -> v -> a.eval(v) % b.eval(v);
                };
                left = new Compiled(node, any(left, right));
            }
        }

        private Compiled unary() {
            if (this.take("-")) {
                final Compiled inner = this.unary();
                final Node a = inner.node();
                return new Compiled(v -> -a.eval(v), inner.dynamic());
            }
            if (this.take("!")) {
                final Compiled inner = this.unary();
                final Node a = inner.node();
                return new Compiled(v -> a.eval(v) == 0f ? 1f : 0f, inner.dynamic());
            }
            if (this.take("+"))
                return this.unary();
            return this.atom();
        }

        private Compiled atom() {
            final String token = this.peek();
            if (token == null)
                throw this.error("unexpected end");
            this.at++;

            if (token.equals("(")) {
                final Compiled inner = this.ternary();
                this.expect(")");
                return inner;
            }
            if (Character.isDigit(token.charAt(0)) || token.charAt(0) == '.') {
                try {
                    return Compiled.constant(Float.parseFloat(token));
                } catch (final NumberFormatException e) {
                    throw this.error("bad number " + token);
                }
            }
            if (!Character.isLetter(token.charAt(0)) && token.charAt(0) != '_')
                throw this.error("unexpected " + token);

            if (this.take("("))
                return this.call(token);
            switch (token) {
                case "pi":
                    return Compiled.constant((float) Math.PI);
                case "tau":
                    return Compiled.constant(TAU);
                case "true":
                    return Compiled.constant(1f);
                case "false":
                    return Compiled.constant(0f);
                default:
                    break;
            }
            final int slot = this.names.slot(token);
            if (slot < 0)
                throw this.error("unknown name " + token);
            return new Compiled(v -> v[slot], this.names.dynamic.get(slot));
        }

        private Compiled call(final String name) {
            final List<Compiled> args = new ArrayList<>();
            if (!this.take(")")) {
                do {
                    args.add(this.ternary());
                } while (this.take(","));
                this.expect(")");
            }
            final Node[] a = args.stream().map(Compiled::node).toArray(Node[]::new);
            final boolean dynamic = args.stream().anyMatch(Compiled::dynamic);

            if (name.equals("rand")) {
                this.arity(name, a, 1);
                final int seed = this.names.slot("seed");
                if (seed < 0)
                    throw this.error("rand() needs a seed, which only points have");
                return new Compiled(v -> random(v[seed], a[0].eval(v)), dynamic || this.names.dynamic.get(seed));
            }
            if (name.equals("pick")) {
                if (a.length < 2)
                    throw this.error("pick() needs an index and at least one value");
                return new Compiled(v -> {
                    final int index = Mth.clamp((int) Math.floor(a[0].eval(v)), 0, a.length - 2);
                    return a[index + 1].eval(v);
                }, dynamic);
            }
            if (name.equals("min") || name.equals("max")) {
                if (a.length < 1)
                    throw this.error(name + "() needs values");
                final boolean min = name.equals("min");
                return new Compiled(v -> {
                    float best = a[0].eval(v);
                    for (int i = 1; i < a.length; i++)
                        best = min ? Math.min(best, a[i].eval(v)) : Math.max(best, a[i].eval(v));
                    return best;
                }, dynamic);
            }

            final Node node = switch (name) {
                case "sin" -> this.one(name, a, x -> (float) Math.sin(x));
                case "cos" -> this.one(name, a, x -> (float) Math.cos(x));
                case "tan" -> this.one(name, a, x -> (float) Math.tan(x));
                case "asin" -> this.one(name, a, x -> (float) Math.asin(x));
                case "acos" -> this.one(name, a, x -> (float) Math.acos(x));
                case "atan" -> this.one(name, a, x -> (float) Math.atan(x));
                case "exp" -> this.one(name, a, x -> (float) Math.exp(x));
                case "log" -> this.one(name, a, x -> (float) Math.log(x));
                case "sqrt" -> this.one(name, a, x -> (float) Math.sqrt(x));
                case "abs" -> this.one(name, a, Math::abs);
                case "sign" -> this.one(name, a, Math::signum);
                case "floor" -> this.one(name, a, x -> (float) Math.floor(x));
                case "ceil" -> this.one(name, a, x -> (float) Math.ceil(x));
                case "round" -> this.one(name, a, x -> (float) Math.floor(x + 0.5f));
                case "fract" -> this.one(name, a, x -> x - (float) Math.floor(x));
                case "smooth" -> this.one(name, a, Expr::smooth);
                case "outback" -> this.one(name, a, ShipAnimation::outBack);
                case "outcubic" -> this.one(name, a, ShipAnimation::outCubic);
                case "atan2" -> this.two(name, a, (y, x) -> (float) Math.atan2(y, x));
                case "pow" -> this.two(name, a, (x, y) -> (float) Math.pow(x, y));
                case "mod" -> this.two(name, a, (x, y) -> x - y * (float) Math.floor(x / y));
                case "step" -> this.two(name, a, (edge, x) -> x < edge ? 0f : 1f);
                case "clamp" -> {
                    this.arity(name, a, 3);
                    yield v -> Mth.clamp(a[0].eval(v), a[1].eval(v), a[2].eval(v));
                }
                case "mix" -> {
                    this.arity(name, a, 3);
                    yield v -> Mth.lerp(a[2].eval(v), a[0].eval(v), a[1].eval(v));
                }
                case "smoothstep" -> {
                    this.arity(name, a, 3);
                    yield v -> {
                        final float lo = a[0].eval(v);
                        final float hi = a[1].eval(v);
                        return smooth((a[2].eval(v) - lo) / (hi - lo));
                    };
                }
                case "length" -> {
                    this.arity(name, a, 3);
                    yield v -> {
                        final float x = a[0].eval(v);
                        final float y = a[1].eval(v);
                        final float z = a[2].eval(v);
                        return Mth.sqrt(x * x + y * y + z * z);
                    };
                }
                case "hash" -> {
                    this.arity(name, a, 3);
                    yield v -> hash(Mth.floor(a[0].eval(v)), Mth.floor(a[1].eval(v)), Mth.floor(a[2].eval(v)));
                }
                case "noise" -> {
                    this.arity(name, a, 3);
                    yield v -> noise(a[0].eval(v), a[1].eval(v), a[2].eval(v));
                }
                case "fbm" -> {
                    this.arity(name, a, 3);
                    yield v -> fbm(a[0].eval(v), a[1].eval(v), a[2].eval(v));
                }
                default -> throw this.error("unknown function " + name);
            };
            return new Compiled(node, dynamic);
        }

        private void arity(final String name, final Node[] args, final int count) {
            if (args.length != count)
                throw this.error(name + "() takes " + count + " values");
        }

        private Node one(final String name, final Node[] a, final Unary f) {
            this.arity(name, a, 1);
            return v -> f.apply(a[0].eval(v));
        }

        private Node two(final String name, final Node[] a, final Binary f) {
            this.arity(name, a, 2);
            return v -> f.apply(a[0].eval(v), a[1].eval(v));
        }

        private static boolean any(final Compiled... parts) {
            for (final Compiled part : parts) {
                if (part.dynamic())
                    return true;
            }
            return false;
        }
    }

    @FunctionalInterface
    private interface Unary {

        float apply(float x);
    }

    @FunctionalInterface
    private interface Binary {

        float apply(float x, float y);
    }
}
