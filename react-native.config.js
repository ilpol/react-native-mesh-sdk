// Явно указываем autolinking'у, где лежит ReactPackage (класс в com.meshchat.rn,
// а не в namespace по умолчанию).
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
