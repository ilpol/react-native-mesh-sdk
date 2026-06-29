// Explicitly tell autolinking where the ReactPackage lives (the class is in
// com.meshchat.rn, not in the default namespace).
module.exports = {
  dependency: {
    platforms: {
      android: {
        packageImportPath: 'import com.meshchat.rn.MeshChatPackage;',
        packageInstance: 'new MeshChatPackage()',
      },
    },
  },
};
