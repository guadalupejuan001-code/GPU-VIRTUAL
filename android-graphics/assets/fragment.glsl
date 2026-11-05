#version 310 es
precision highp float;
in vec2 pointLocal;
in vec3 pointColor;
layout(location=0) out vec4 color;
void main() {
    float r=dot(pointLocal,pointLocal);
    if(r>1.0)discard;
    float glow=exp(-4.5*r)*0.70+pow(max(0.0,1.0-r*5.0),3.0)*0.30;
    color=vec4(pointColor,glow);
}
