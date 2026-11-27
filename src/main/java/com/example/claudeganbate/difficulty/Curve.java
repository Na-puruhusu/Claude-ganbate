package com.example.claudeganbate.difficulty;

/** 区分線形カーブ。x は 0..(ys.length-1) のレベル。範囲外は端の値に丸める。 */
public final class Curve {
    private Curve() {}

    public static double at(double[] ys, double x) {
        if (x <= 0) return ys[0];
        if (x >= ys.length - 1) return ys[ys.length - 1];
        int i = (int) Math.floor(x);
        double f = x - i;
        return ys[i] + (ys[i + 1] - ys[i]) * f;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
