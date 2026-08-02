// Fullscreen triangle from gl_VertexID, no vertex buffer. Pushed to the far plane so any
// real geometry drawn afterwards wins the depth test.

out vec2 vNdc;

const vec2 POS[3] = vec2[](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));

void main() {
    vec2 p = POS[gl_VertexID];
    vNdc = p;
    gl_Position = vec4(p, 0.99995, 1.0);
}
