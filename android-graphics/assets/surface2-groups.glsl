layout(local_size_x=1) in;
layout(std430,binding=1) buffer Builder {uint meta[];};
void main(){
    uint refs=0u,pages=0u;
    for(uint i=0u;i<groupCount();++i){uint at=groupsAt()+i*4u;meta[at+2u]=refs;meta[at+3u]=pages;refs+=meta[at];pages+=meta[at+1u];}
    meta[0]=pages;meta[3]=meta[1];meta[1]=refs;meta[2]=residentWords();
}
