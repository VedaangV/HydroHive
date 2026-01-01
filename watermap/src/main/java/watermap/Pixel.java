package watermap;

public class Pixel {
    int px;
    int py;
    double pH;
    double turbidity;
    double tds;
    double temp;
    double abi;
    double weight;
    String timestamp;

    Pixel(int px, int py) {
        this.px = px;
        this.py = py;
    }

    void updateVals(
            double pH,
            double turbidity,
            double tds,
            double temp,
            double w,
            String timestamp
    ) {
        if (weight == 0) {
            this.pH = pH;
            this.turbidity = turbidity;
            this.tds = tds;
            this.temp = temp;
            this.timestamp = timestamp;
        } else {
            this.pH = (this.pH * weight + pH * w) / (weight + w);
            this.turbidity = (this.turbidity * weight + turbidity * w) / (weight + w);
            this.tds = (this.tds * weight + tds * w) / (weight + w);
            this.temp = (this.temp * weight + temp * w) / (weight + w);
            this.timestamp = timestamp;
        }

        this.abi = Grid.getABI(this.pH, this.turbidity, this.tds, this.temp);
        weight += w;
        weight = Math.min(1, weight);
    }
}
