#include "libzpaq.h"
#include <stdio.h>
#include <stdlib.h>
#include <string>
#include <stdexcept>
void libzpaq::error(const char* msg) { fprintf(stderr, "error: %s\n", msg); exit(2); }
struct In: libzpaq::Reader { FILE* f; int get() { return getc(f); } int read(char* b, int n) { return fread(b,1,n,f);} };
struct Out: libzpaq::Writer { FILE* f; void put(int c) { putc(c,f);} void write(const char* b,int n){fwrite(b,1,n,f);} };
int main(int argc, char** argv) {
  // refc c method in out [filename comment] | refc d in out
  In in; Out out;
  if (argv[1][0]=='c') {
    in.f=fopen(argv[3],"rb"); out.f=fopen(argv[4],"wb");
    libzpaq::compress(&in,&out,argv[2], argc>5?argv[5]:0, argc>6?argv[6]:0, true);
  } else {
    in.f=fopen(argv[2],"rb"); out.f=fopen(argv[3],"wb");
    libzpaq::decompress(&in,&out);
  }
  fclose(out.f);
  return 0;
}
