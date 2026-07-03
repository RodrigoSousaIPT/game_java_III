package com.arenabot.brain;

/**
 * Tiny vector math library for cosine search over the 15-section manual.
 * Implemented locally so we don't drag in nd4j or commons-math3 — the vectors
 * here are at most ~700-D (embedding model output) and dim is fixed up front.
 */
public final class VectorMath {

    private VectorMath() {}

    public static float[] zero(int dim) {
        return new float[dim];
    }

    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dim mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Top-k cosine search returning indices into {@code corpus} in descending similarity. */
    public static int[] topK(float[] query, float[][] corpus, int k) {
        double[] scores = new double[corpus.length];
        for (int i = 0; i < corpus.length; i++) scores[i] = cosine(query, corpus[i]);
        Integer[] order = new Integer[corpus.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Double.compare(scores[y], scores[x]));
        int[] out = new int[Math.min(k, order.length)];
        for (int i = 0; i < out.length; i++) out[i] = order[i];
        return out;
    }
}
