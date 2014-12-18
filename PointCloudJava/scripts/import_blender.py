# Script for importing point cloud data from our Tango app.
# The format of the file is very simple:
#
# <x1>,<y1>,<z1>\n
# <x2>,<y2>,<z2>\n
# ...
#

import bpy
path='E:\\Desktop\\20141215_171549.txt'

try:
    f = open(path, 'r')
except:
    print ("Cound not open the specified file.")

ve = []
for line in f:
    try:
        read = f.readline().split(',') 
        ve.extend( [float(read[0]), float(read[1]), float(read[2])] )
    except:
        print ("Error reading line: %s" % read)

mesh = bpy.data.meshes.new("Cube")
mesh.vertices.add(len(ve) / 3)
mesh.vertices.foreach_set("co", ve)
obj = bpy.data.objects.new("My Data", mesh)
# Link object to current scene
bpy.context.scene.objects.link(obj)
