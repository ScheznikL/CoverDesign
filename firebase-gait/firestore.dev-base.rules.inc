    // Minimal existing app paths for a NEW DEVELOPMENT database only.
    // Account roles are self-selected; an explicit gaitShare is still required.
    function devValidProfile(data, uid) {
      return data.keys().hasAll(['uid', 'email', 'displayName', 'phone', 'role', 'createdAt'])
        && data.keys().hasOnly(['uid', 'email', 'displayName', 'phone', 'role', 'createdAt', 'hasInsoles'])
        && data.uid == uid && data.role in ['PATIENT', 'DOCTOR']
        && data.email is string && data.email.size() <= 320
        && data.displayName is string && data.displayName.size() <= 120
        && data.phone is string && data.phone.size() <= 80
        && gaitMillis(data.createdAt)
        && (!('hasInsoles' in data) || data.hasInsoles is bool);
    }
    match /users/{uid} {
      // Missing own profiles may be read, then created after Firebase Auth login.
      allow get: if gaitOwns(uid);
      allow list, delete: if false;
      allow create: if gaitOwns(uid) && devValidProfile(request.resource.data, uid);
      allow update: if gaitOwns(uid) && devValidProfile(request.resource.data, uid)
        && request.resource.data.uid == resource.data.uid
        && request.resource.data.role == resource.data.role
        && request.resource.data.createdAt == resource.data.createdAt;

      match /configs/{configId} {
        allow get, list, create, update, delete: if gaitOwns(uid);
      }
      match /orders/{orderId} {
        allow get, list, create, update, delete: if gaitOwns(uid);
      }
    }
