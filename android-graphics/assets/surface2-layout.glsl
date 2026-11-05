// Builder: header[4], {page,offset,count}[tiles], reverse[tiles],
// projected bounds[4*bodies], {sumRefs,sumPages,prefixRefs,prefixPages}[groups].
// Resident map: header[4], tile records, reverse pages, compact candidate IDs.
uint recordAt(uint tile){return 4u+tile*3u;}
uint reverseAt(){return 4u+tileCount()*3u;}
uint boundsAt(){return 4u+tileCount()*4u;}
uint groupsAt(){return boundsAt()+frameInfo.z*4u;}
uint groupCount(){return (tileCount()+127u)/128u;}
uint builderWords(){return groupsAt()+groupCount()*4u;}
uint residentWords(){return 4u+tileCount()*4u;}
