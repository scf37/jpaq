#include "libzpaq.h"
#include <stdio.h>
#include <stdlib.h>
#include <string>
void libzpaq::error(const char* msg) { fprintf(stderr, "error: %s\n", msg); exit(2); }
struct In: libzpaq::Reader { FILE* f; int get() { return getc(f); } int read(char* b, int n) { return fread(b,1,n,f);} };
struct Out: libzpaq::Writer { FILE* f; void put(int c) { putc(c,f);} void write(const char* b,int n){fwrite(b,1,n,f);} };
int main(int argc, char** argv) {
  // refz c config.cfg in out [args...]  |  refz d in out
  In in; Out out;
  if (argv[1][0]=='c') {
    FILE* cf=fopen(argv[2],"rb"); std::string cfg; int c; while ((c=getc(cf))!=EOF) cfg+=char(c); fclose(cf);
    in.f=fopen(argv[3],"rb"); out.f=fopen(argv[4],"wb");
    int args[9]={0}; for (int i=5;i<argc&&i<14;++i) args[i-5]=atoi(argv[i]);
    libzpaq::Compressor co; co.setOutput(&out); co.writeTag();
    libzpaq::StringBuffer pc;
    co.startBlock(cfg.c_str(), args, &pc);
    co.startSegment("f","c");
    co.setInput(&in); co.compress(); co.endSegment(0); co.endBlock();
  } else {
    in.f=fopen(argv[2],"rb"); out.f=fopen(argv[3],"wb");
    libzpaq::decompress(&in,&out);
  }
  fclose(out.f);
}
