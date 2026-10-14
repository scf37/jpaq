package io.github.scf37.jpaq.libzpaq;

/** Receives decoded bytes, loads the PCOMP program, then runs it on the data. */
final class PostProcessor {
  private int state;   // input parse state: 0=INIT, 1=PASS, 2..4=loading, 5=POST
  private int hsize;   // header size
  private int ph;
  private int pm;      // sizes of H and M in z
  final Zpaql z = new Zpaql();

  void init(int h, int m) {
    state = hsize = 0;
    ph = h;
    pm = m;
    z.clear();
  }

  int getState() {
    return state;
  }

  void setOutput(ZpaqWriter out) {
    z.output = out;
  }

  void setSha1(Sha1 sha1) {
    z.sha1 = sha1;
  }

  // (PASS=0 | PROG=1 psize[0..1] pcomp[0..psize-1]) data... EOB=-1
  // Return state: 1=PASS, 2..4=loading PROG, 5=PROG loaded
  int write(int c) {
    switch (state) {
      case 0 -> {
        if (c < 0) throw ZpaqException.error("Unexpected EOS");
        state = c + 1;
        if (state > 2) throw ZpaqException.error("unknown post processing type");
        if (state == 1) z.clear();
      }
      case 1 -> z.write1(c);
      case 2 -> {
        if (c < 0) throw ZpaqException.error("Unexpected EOS");
        hsize = c;
        state = 3;
      }
      case 3 -> {
        if (c < 0) throw ZpaqException.error("Unexpected EOS");
        hsize += c * 256;
        if (hsize < 1) throw ZpaqException.error("Empty PCOMP");
        z.header = new byte[hsize + 300];
        z.cend = 8;
        z.hbegin = z.hend = z.cend + 128;
        z.header[4] = (byte) ph;
        z.header[5] = (byte) pm;
        state = 4;
      }
      case 4 -> {
        if (c < 0) throw ZpaqException.error("Unexpected EOS");
        z.header[z.hend++] = (byte) c;
        if (z.hend - z.hbegin == hsize) {
          hsize = z.cend - 2 + z.hend - z.hbegin;
          z.header[0] = (byte) (hsize & 255);
          z.header[1] = (byte) (hsize >> 8);
          z.initp();
          state = 5;
        }
      }
      case 5 -> {
        z.run(c);
        if (c < 0) z.flush();
      }
      default -> throw new AssertionError();
    }
    return state;
  }
}
