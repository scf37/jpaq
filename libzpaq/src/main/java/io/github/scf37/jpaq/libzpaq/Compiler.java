package io.github.scf37.jpaq.libzpaq;

/**
 * Compiles ZPAQL source code (COMP, HCOMP, optional PCOMP) into the byte code stored in ZPAQ
 * block headers (libzpaq::Compiler).
 */
final class Compiler {
  // Symbolic constants (opcodes above 255 are pseudo instructions)
  private static final int JT = 39;
  private static final int JF = 47;
  private static final int JMP = 63;
  private static final int LJ = 255;
  private static final int POST = 256;
  private static final int PCOMP = 257;
  private static final int END = 258;
  private static final int IF = 259;
  private static final int IFNOT = 260;
  private static final int ELSE = 261;
  private static final int ENDIF = 262;
  private static final int DO = 263;
  private static final int WHILE = 264;
  private static final int UNTIL = 265;
  private static final int FOREVER = 266;
  private static final int IFL = 267;
  private static final int IFNOTL = 268;
  private static final int ELSEL = 269;

  // Opcodes
  static final String[] OPCODES = {
      "error", "a++", "a--", "a!", "a=0", "", "", "a=r",
      "b<>a", "b++", "b--", "b!", "b=0", "", "", "b=r",
      "c<>a", "c++", "c--", "c!", "c=0", "", "", "c=r",
      "d<>a", "d++", "d--", "d!", "d=0", "", "", "d=r",
      "*b<>a", "*b++", "*b--", "*b!", "*b=0", "", "", "jt",
      "*c<>a", "*c++", "*c--", "*c!", "*c=0", "", "", "jf",
      "*d<>a", "*d++", "*d--", "*d!", "*d=0", "", "", "r=a",
      "halt", "out", "", "hash", "hashd", "", "", "jmp",
      "a=a", "a=b", "a=c", "a=d", "a=*b", "a=*c", "a=*d", "a=",
      "b=a", "b=b", "b=c", "b=d", "b=*b", "b=*c", "b=*d", "b=",
      "c=a", "c=b", "c=c", "c=d", "c=*b", "c=*c", "c=*d", "c=",
      "d=a", "d=b", "d=c", "d=d", "d=*b", "d=*c", "d=*d", "d=",
      "*b=a", "*b=b", "*b=c", "*b=d", "*b=*b", "*b=*c", "*b=*d", "*b=",
      "*c=a", "*c=b", "*c=c", "*c=d", "*c=*b", "*c=*c", "*c=*d", "*c=",
      "*d=a", "*d=b", "*d=c", "*d=d", "*d=*b", "*d=*c", "*d=*d", "*d=",
      "", "", "", "", "", "", "", "",
      "a+=a", "a+=b", "a+=c", "a+=d", "a+=*b", "a+=*c", "a+=*d", "a+=",
      "a-=a", "a-=b", "a-=c", "a-=d", "a-=*b", "a-=*c", "a-=*d", "a-=",
      "a*=a", "a*=b", "a*=c", "a*=d", "a*=*b", "a*=*c", "a*=*d", "a*=",
      "a/=a", "a/=b", "a/=c", "a/=d", "a/=*b", "a/=*c", "a/=*d", "a/=",
      "a%=a", "a%=b", "a%=c", "a%=d", "a%=*b", "a%=*c", "a%=*d", "a%=",
      "a&=a", "a&=b", "a&=c", "a&=d", "a&=*b", "a&=*c", "a&=*d", "a&=",
      "a&~a", "a&~b", "a&~c", "a&~d", "a&~*b", "a&~*c", "a&~*d", "a&~",
      "a|=a", "a|=b", "a|=c", "a|=d", "a|=*b", "a|=*c", "a|=*d", "a|=",
      "a^=a", "a^=b", "a^=c", "a^=d", "a^=*b", "a^=*c", "a^=*d", "a^=",
      "a<<=a", "a<<=b", "a<<=c", "a<<=d", "a<<=*b", "a<<=*c", "a<<=*d", "a<<=",
      "a>>=a", "a>>=b", "a>>=c", "a>>=d", "a>>=*b", "a>>=*c", "a>>=*d", "a>>=",
      "a==a", "a==b", "a==c", "a==d", "a==*b", "a==*c", "a==*d", "a==",
      "a<a", "a<b", "a<c", "a<d", "a<*b", "a<*c", "a<*d", "a<",
      "a>a", "a>b", "a>c", "a>d", "a>*b", "a>*c", "a>*d", "a>",
      "", "", "", "", "", "", "", "",
      "", "", "", "", "", "", "", "lj",
      "post", "pcomp", "end", "if", "ifnot", "else", "endif", "do",
      "while", "until", "forever", "ifl", "ifnotl", "elsel", ";", null};

  private final String in;   // ZPAQL source code
  private int pos;           // current position in in
  private final int[] args;  // up to 9 arguments or null
  private final Zpaql hz;    // output of COMP and HCOMP sections
  private final Zpaql pz;    // output of PCOMP section
  private final ZpaqWriter out2;  // output of "PCOMP ... ;"
  private int line = 1;      // input line number for reporting errors
  private int state;         // parse state: 0=space -1=word >0 (nest level)

  /** Fixed capacity stack of 16 bit values. */
  private static final class Stack {
    private final char[] s;
    private int top;

    Stack(int n) {
      s = new char[n];
    }

    void push(int x) {
      if (top >= s.length) throw ZpaqException.error("IF or DO nested too deep");
      s[top++] = (char) x;
    }

    int pop() {
      if (top <= 0) throw ZpaqException.error("unmatched IF or DO");
      return s[--top];
    }
  }

  private final Stack ifStack = new Stack(1000);
  private final Stack doStack = new Stack(1000);

  private int ch(int i) {
    return i < in.length() ? in.charAt(i) : 0;
  }

  // Advance in to start of next token. Tokens are delimited by white space. Comments enclosed
  // in ((nested) parenthesis) are skipped.
  private void next() {
    for (; ch(pos) != 0; ++pos) {
      int c = ch(pos);
      if (c == '\n') ++line;
      if (c == '(') state += 1 + (state < 0 ? 1 : 0);
      else if (state > 0 && c == ')') --state;
      else if (state < 0 && c <= ' ') state = 0;
      else if (state == 0 && c > ' ') {
        state = -1;
        break;
      }
    }
    if (ch(pos) == 0) throw ZpaqException.error("unexpected end of config");
  }

  private static int tolower(int c) {
    return (c >= 'A' && c <= 'Z') ? c + 'a' - 'A' : c;
  }

  // return true if in==word up to white space or '(', case insensitive
  private boolean matchToken(String word) {
    int a = pos;
    int w = 0;
    for (; ch(a) > ' ' && ch(a) != '(' && w < word.length(); ++a, ++w)
      if (tolower(ch(a)) != tolower(word.charAt(w))) return false;
    return w == word.length() && (ch(a) <= ' ' || ch(a) == '(');
  }

  private static String trunc(String s, int n) {
    return s.length() > n ? s.substring(0, n) : s;
  }

  // Report an error with the line number and the token being read
  private ZpaqException syntaxError(String msg, String expected) {
    StringBuilder s = new StringBuilder("Config line ");
    s.append(line).append(" at ");
    for (int i = s.length(); i < 40 && ch(pos) > ' '; ++i) s.append((char) ch(pos++));
    s.append(": ").append(trunc(msg, 40));
    if (expected != null) s.append(", expected: ").append(trunc(expected, 20));
    return ZpaqException.error(s.toString());
  }

  private ZpaqException syntaxError(String msg) {
    return syntaxError(msg, null);
  }

  // Read a token, which must be in the null terminated list. Return its index.
  private int rtoken(String[] list) {
    next();
    for (int i = 0; list[i] != null; ++i)
      if (matchToken(list[i])) return i;
    throw syntaxError("unexpected");
  }

  // Read a token which must be the specified value s
  private void rtoken(String s) {
    next();
    if (!matchToken(s)) throw syntaxError("expected", s);
  }

  // C atoi(): optional white space, sign, digits
  private int atoi(int p) {
    while (ch(p) == ' ' || (ch(p) >= 9 && ch(p) <= 13)) ++p;
    boolean neg = false;
    if (ch(p) == '-') {
      neg = true;
      ++p;
    } else if (ch(p) == '+') {
      ++p;
    }
    int r = 0;
    while (ch(p) >= '0' && ch(p) <= '9') r = r * 10 + (ch(p++) - '0');
    return neg ? -r : r;
  }

  // Read a number in (low...high) or fail. For numbers like $N+M, return arg[N-1]+M
  private int rtoken(int low, int high) {
    next();
    int r = 0;
    if (ch(pos) == '$' && ch(pos + 1) >= '1' && ch(pos + 1) <= '9') {
      if (ch(pos + 2) == '+') r = atoi(pos + 3);
      if (args != null) r += args[ch(pos + 1) - '1'];
    } else if (ch(pos) == '-' || (ch(pos) >= '0' && ch(pos) <= '9')) {
      r = atoi(pos);
    } else {
      throw syntaxError("expected a number");
    }
    if (r < low) throw syntaxError("number too low");
    if (r > high) throw syntaxError("number too high");
    return r;
  }

  // Compile HCOMP or PCOMP code. Return code for end token (POST, PCOMP, END)
  private int compileComp(Zpaql z) {
    int op;
    final int compBegin = z.hend;
    final byte[] hd = z.header;
    while (true) {
      op = rtoken(OPCODES);
      if (op == POST || op == PCOMP || op == END) break;
      int operand = -1;   // 0...255 if 2 bytes
      int operand2 = -1;  // 0...255 if 3 bytes
      if (op == IF) {
        op = JF;
        operand = 0;  // set later
        ifStack.push(z.hend + 1);  // save jump target location
      } else if (op == IFNOT) {
        op = JT;
        operand = 0;
        ifStack.push(z.hend + 1);
      } else if (op == IFL || op == IFNOTL) {  // long if
        if (op == IFL) hd[z.hend++] = (byte) JT;
        if (op == IFNOTL) hd[z.hend++] = (byte) JF;
        hd[z.hend++] = 3;
        op = LJ;
        operand = operand2 = 0;
        ifStack.push(z.hend + 1);
      } else if (op == ELSE || op == ELSEL) {
        if (op == ELSE) {
          op = JMP;
          operand = 0;
        } else {
          op = LJ;
          operand = operand2 = 0;
        }
        int a = ifStack.pop();  // conditional jump target location
        if ((hd[a - 1] & 255) != LJ) {  // IF, IFNOT
          int j = z.hend - a + 1 + (op == LJ ? 1 : 0);  // offset at IF
          if (j > 127) throw syntaxError("IF too big, try IFL, IFNOTL");
          hd[a] = (byte) j;
        } else {  // IFL, IFNOTL
          int j = z.hend - compBegin + 2 + (op == LJ ? 1 : 0);
          hd[a] = (byte) (j & 255);
          hd[a + 1] = (byte) ((j >> 8) & 255);
        }
        ifStack.push(z.hend + 1);  // save JMP target location
      } else if (op == ENDIF) {
        int a = ifStack.pop();  // jump target address
        int j = z.hend - a - 1;  // jump offset
        if ((hd[a - 1] & 255) != LJ) {
          if (j > 127) throw syntaxError("IF too big, try IFL, IFNOTL, ELSEL\n");
          hd[a] = (byte) j;
        } else {
          j = z.hend - compBegin;
          hd[a] = (byte) (j & 255);
          hd[a + 1] = (byte) ((j >> 8) & 255);
        }
      } else if (op == DO) {
        doStack.push(z.hend);
      } else if (op == WHILE || op == UNTIL || op == FOREVER) {
        int a = doStack.pop();
        int j = a - z.hend - 2;
        if (j >= -127) {  // backward short jump
          if (op == WHILE) op = JT;
          if (op == UNTIL) op = JF;
          if (op == FOREVER) op = JMP;
          operand = j & 255;
        } else {  // backward long jump
          j = a - compBegin;
          if (op == WHILE) {
            hd[z.hend++] = (byte) JF;
            hd[z.hend++] = 3;
          }
          if (op == UNTIL) {
            hd[z.hend++] = (byte) JT;
            hd[z.hend++] = 3;
          }
          op = LJ;
          operand = j & 255;
          operand2 = j >> 8;
        }
      } else if ((op & 7) == 7) {  // 2 byte operand, read N
        if (op == LJ) {
          operand = rtoken(0, 65535);
          operand2 = operand >> 8;
          operand &= 255;
        } else if (op == JT || op == JF || op == JMP) {
          operand = rtoken(-128, 127);
          operand &= 255;
        } else {
          operand = rtoken(0, 255);
        }
      }
      if (op >= 0 && op <= 255) hd[z.hend++] = (byte) op;
      if (operand >= 0) hd[z.hend++] = (byte) operand;
      if (operand2 >= 0) hd[z.hend++] = (byte) operand2;
      if (z.hend >= hd.length - 130 || z.hend - z.hbegin + z.cend - 2 > 65535)
        throw syntaxError("program too big");
    }
    hd[z.hend++] = 0;  // END
    return op;
  }

  /**
   * Compiles ZPAQL source {@code in} with arguments {@code args} (null = all 0). The COMP and
   * HCOMP sections go to {@code hz}, the PCOMP section to {@code pz} and the "PCOMP cmd ;"
   * command text to {@code out2} (if not null).
   */
  Compiler(String in, int[] args, Zpaql hz, Zpaql pz, ZpaqWriter out2) {
    this.in = in;
    this.args = args;
    this.hz = hz;
    this.pz = pz;
    this.out2 = out2;
    hz.clear();
    pz.clear();
    hz.header = new byte[68000];

    // Compile the COMP section of header
    rtoken("comp");
    hz.header[2] = (byte) rtoken(0, 255);  // hh
    hz.header[3] = (byte) rtoken(0, 255);  // hm
    hz.header[4] = (byte) rtoken(0, 255);  // ph
    hz.header[5] = (byte) rtoken(0, 255);  // pm
    final int n = rtoken(0, 255);
    hz.header[6] = (byte) n;  // n
    hz.cend = 7;
    for (int i = 0; i < n; ++i) {
      rtoken(i, i);
      int type = rtoken(Components.NAMES_NULL_TERMINATED);
      hz.header[hz.cend++] = (byte) type;
      int clen = Components.size(type & 255);
      if (clen < 1 || clen > 10) throw syntaxError("invalid component");
      for (int j = 1; j < clen; ++j) hz.header[hz.cend++] = (byte) rtoken(0, 255);
    }
    hz.cend++;  // end
    hz.hbegin = hz.hend = hz.cend + 128;

    // Compile HCOMP
    rtoken("hcomp");
    int op = compileComp(hz);

    // Compute header size
    int hsize = hz.cend - 2 + hz.hend - hz.hbegin;
    hz.header[0] = (byte) (hsize & 255);
    hz.header[1] = (byte) (hsize >> 8);

    if (op == POST) {  // Compile POST 0 END
      rtoken(0, 0);
      rtoken("end");
    } else if (op == PCOMP) {  // Compile PCOMP pcomp_cmd ; program... END
      pz.header = new byte[68000];
      pz.header[4] = hz.header[4];  // ph
      pz.header[5] = hz.header[5];  // pm
      pz.cend = 8;
      pz.hbegin = pz.hend = pz.cend + 128;

      // get pcomp_cmd ending with ";" (case sensitive)
      next();
      while (ch(pos) != 0 && ch(pos) != ';') {
        if (out2 != null) out2.put(ch(pos));
        ++pos;
      }
      if (ch(pos) != 0) ++pos;

      // Compile PCOMP
      op = compileComp(pz);
      int len = pz.cend - 2 + pz.hend - pz.hbegin;  // insert header size
      pz.header[0] = (byte) (len & 255);
      pz.header[1] = (byte) (len >> 8);
      if (op != END) throw syntaxError("expected END");
    } else if (op != END) {
      throw syntaxError("expected END or POST 0 END or PCOMP cmd ; ...");
    }
  }
}
