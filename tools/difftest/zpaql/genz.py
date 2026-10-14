import random, sys
seed=int(sys.argv[1]); rnd=random.Random(seed)
regs=['a','b','c','d']
def src_ops(allow_n=True):
    s=['a','b','c','d','*b','*c','*d']
    return s
def n(): return rnd.choice([0,1,2,3,7,8,15,16,31,32,100,127,128,200,254,255, rnd.randint(0,255)])
def gen_ops(count, depth=0):
    out=[]
    for _ in range(count):
        k=rnd.random()
        if k<0.12:   # unary
            r=rnd.choice(['a','b','c','d','*b','*c','*d'])
            op=rnd.choice(['++','--','!','=0'])
            out.append(r+op)
        elif k<0.20:  # swap
            r=rnd.choice(['b','c','d','*b','*c','*d'])
            out.append(r+'<>a')
        elif k<0.38:  # assignment
            dst=rnd.choice(['a','b','c','d','*b','*c','*d'])
            srcs=['a','b','c','d','*b','*c','*d']
            s=rnd.choice(srcs)
            if s==dst or (dst=='a' and s=='a'): s='b' if dst!='b' else 'c'
            if rnd.random()<0.35: out.append(dst+'= '+str(n()))
            else: out.append(dst+'='+s)
        elif k<0.62:  # binary on a
            op=rnd.choice(['+=','-=','*=','/=','%=','&=','&~','|=','^=','<<=','>>='])
            if rnd.random()<0.35: out.append('a'+op+' '+str(n()))
            else: out.append('a'+op+rnd.choice(['a','b','c','d','*b','*c','*d']))
        elif k<0.68:  # r access
            if rnd.random()<0.5: out.append('a=r '+str(rnd.randint(0,255)))
            else: out.append('r=a '+str(rnd.randint(0,255)))
            if rnd.random()<0.3: out.append(rnd.choice(['b','c','d'])+'=r '+str(rnd.randint(0,255)))
        elif k<0.72:
            out.append(rnd.choice(['hash','hashd']))
        elif k<0.86 and depth<2:  # if blocks
            cmp=rnd.choice(['==','<','>'])
            if rnd.random()<0.4: c='a'+cmp+' '+str(n())
            else: c='a'+cmp+rnd.choice(['a','b','c','d','*b','*c','*d'])
            kw=rnd.choice(['if','ifnot'])
            body=gen_ops(rnd.randint(1,5),depth+1)
            if rnd.random()<0.5:
                els=gen_ops(rnd.randint(1,4),depth+1)
                out.append(c+' '+kw+' '+' '.join(body)+' else '+' '.join(els)+' endif')
            else:
                out.append(c+' '+kw+' '+' '.join(body)+' endif')
        elif False:
            body=gen_ops(rnd.randint(1,3),depth+1)
            out.append('r=a 250 c='+'  '+str(rnd.randint(1,4))+' do '+' '.join(body)+' c-- a=c a> 0 while a=r 250')
        else:
            out.append(rnd.choice(['a=a','b=b','c=c','d=d']))
    return out
nc=rnd.randint(1,3)
comps=[]; hc=[]
sizes=[]
i=0
types=rnd.sample(['icm','cm','isse','mix2','sse'],k=3)
comp_lines=[]
comp_lines.append('0 icm %d'%rnd.randint(2,8))
nn=1
if rnd.random()<0.8: comp_lines.append('%d cm %d %d'%(nn,rnd.randint(4,10),rnd.randint(1,40))); nn+=1
if rnd.random()<0.6: comp_lines.append('%d isse %d %d'%(nn,rnd.randint(2,7),rnd.randint(0,nn-1))); nn+=1
if rnd.random()<0.5: comp_lines.append('%d match %d %d'%(nn,rnd.randint(4,10),rnd.randint(8,14))); nn+=1
if rnd.random()<0.5: comp_lines.append('%d mix2 %d %d %d %d 255'%(nn,rnd.randint(0,6),nn-1,0,rnd.randint(5,40))) if nn>=2 else None; nn+=1 if nn>=2 else 0
# fix mix2 j k order: j,k < nn
cfg='comp %d %d %d %d %d\n'%(rnd.randint(2,6),rnd.randint(4,12),rnd.randint(2,6),rnd.randint(4,12),nn)
cfg+='\n'.join(comp_lines)+'\n'
cfg+='hcomp\n'
h=gen_ops(rnd.randint(5,40))
cfg+='  '+' '.join(h)+'\n'
for c in range(nn):
    cfg+='  d= %d *d=a a+=b a^=c a+= %d\n'%(c,rnd.randint(0,255)) if rnd.random()<0.5 else '  d= %d a+=*b *d=a\n'%c
cfg+='  halt\n'
cfg+='pcomp myfilter ;\n  a> 255 if halt endif\n'
p=gen_ops(rnd.randint(8,50))
cfg+='  '+' '.join(p)+'\n  a=b out\n  halt\nend\n'
sys.stdout.write(cfg)
